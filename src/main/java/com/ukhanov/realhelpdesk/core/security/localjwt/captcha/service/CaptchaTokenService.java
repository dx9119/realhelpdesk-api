package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.config.ConfigValidator;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaReason;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * Подпись и проверка токенов капчи (stateless: ответы и соль не хранятся, состояние — только подписи и
 * {@code iat/exp}). Два типа токенов под одним секретом:
 *
 * <ul>
 * <li><b>challenge</b> — задание, выданное клиенту: {@code typ=challenge}, действие ({@code act}), id ({@code cid}) и
 * уровень ({@code lvl}): pow несёт соль ({@code salt}) и сложность ({@code dif}); emoji — текст задания ({@code pr}),
 * девять глифов ({@code cl}) и HMAC ответа ({@code ah});</li>
 * <li><b>captcha-токен</b> — доказательство решения, предъявляемое на бизнес-операции: {@code typ=captcha}, то же
 * {@code act}, свой одноразовый id ({@code jti}), уровень-источник ({@code src}) и время выдачи challenge
 * ({@code ciat}) для проверки минимального времени заполнения.</li>
 * </ul>
 *
 * <p>
 * Тип в подписи не даёт подменить challenge токеном (и наоборот), привязка {@code act} — не пропустить токен чужого
 * действия; просрочка и подделка подписи ловятся разбором JWT. Ответ эмодзи-задания хранится только в виде HMAC —
 * сервер не помнит правильные ячейки, сверяет присланные индексы по подписанному хэшу.
 * </p>
 */
@Service
public class CaptchaTokenService {

    /** Токен-задание (соль/сложность или эмодзи-задание), выдаётся на POST /api/v1/captcha/challenge. */
    public static final String TYPE_CHALLENGE = "challenge";

    /** Решённое доказательство, предъявляется на register / email-code. */
    public static final String TYPE_CAPTCHA = "captcha";

    /** Действия, на которые выпускаются токены: токен register не принимается на email-code и наоборот. */
    public static final String ACTION_REGISTER = "register";
    public static final String ACTION_EMAIL_CODE = "email-code";

    /** Уровень challenge и источник токена: Proof-of-Work. */
    public static final String LEVEL_POW = "pow";

    /** Уровень challenge и источник токена: выбор глифов из эмодзи-задания (эскалация по риску). */
    public static final String LEVEL_EMOJI = "emoji";

    /** Верхняя граница сложности PoW: число ведущих нулевых бит SHA-256. */
    public static final int MAX_POW_DIFFICULTY = 26;

    private static final int MIN_TTL_SECONDS = 10;
    private static final int MAX_TTL_SECONDS = 86_400;
    private static final int MAX_MIN_FILL_SECONDS = 3_600;

    private static final Set<String> ACTIONS = Set.of(ACTION_REGISTER, ACTION_EMAIL_CODE);
    private static final Set<String> LEVELS = Set.of(LEVEL_POW, LEVEL_EMOJI);

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ACTION = "act";
    private static final String CLAIM_CHALLENGE_ID = "cid";
    private static final String CLAIM_SALT = "salt";
    private static final String CLAIM_DIFFICULTY = "dif";
    private static final String CLAIM_LEVEL = "lvl";
    private static final String CLAIM_PROMPT = "pr";
    private static final String CLAIM_CELLS = "cl";
    private static final String CLAIM_ANSWER_HASH = "ah";
    private static final String CLAIM_SOURCE = "src";
    private static final String CLAIM_JTI = "jti";
    private static final String CLAIM_CHALLENGE_ISSUED_AT = "ciat";

    private static final String CELLS_SEPARATOR = "|";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int ID_BYTES = 16;
    private static final int CELLS_MIN = 2;
    private static final int CELLS_MAX = 16;

    private final SecretKey captchaSecretKey;
    private final CaptchaProperties properties;
    private final SecureRandom random = new SecureRandom();

    public CaptchaTokenService(SecretKey captchaSecretKey, CaptchaProperties properties) {
        this.captchaSecretKey = captchaSecretKey;
        this.properties = properties;
        Integer difficulty = properties.getPowDifficulty();
        Integer challengeTtl = properties.getChallengeTtlSeconds();
        Integer tokenTtl = properties.getTokenTtlSeconds();
        Integer minFill = properties.getMinFillSeconds();
        ConfigValidator.requireInRange(difficulty, 1, MAX_POW_DIFFICULTY, "captcha.pow-difficulty");
        ConfigValidator.requireInRange(challengeTtl, MIN_TTL_SECONDS, MAX_TTL_SECONDS,
                "captcha.challenge-ttl-seconds");
        ConfigValidator.requireInRange(tokenTtl, MIN_TTL_SECONDS, MAX_TTL_SECONDS, "captcha.token-ttl-seconds");
        ConfigValidator.requireInRange(minFill, 0, MAX_MIN_FILL_SECONDS, "captcha.min-fill-seconds");
    }

    /** Случайный 128-битный идентификатор в hex: id challenge или id токена. */
    public String randomId() {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Подписанный PoW-challenge на действие: сложность берётся из конфигурации (клиент её не выбирает). */
    public String issueChallenge(String action, String challengeId, String salt) {
        return issueChallenge(action, challengeId, salt, properties.getPowDifficulty());
    }

    /**
     * Подписанный PoW-challenge с явной сложностью: используется для усложнения задания при мягком риске IP (см.
     * {@code CaptchaRiskService}). Клиент выбирать сложность не может — значение задаёт сервер.
     */
    public String issueChallenge(String action, String challengeId, String salt, int difficulty) {
        requireAction(action);
        requirePowDifficulty(difficulty);
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_TYPE, TYPE_CHALLENGE)
                .claim(CLAIM_ACTION, action)
                .claim(CLAIM_CHALLENGE_ID, challengeId)
                .claim(CLAIM_LEVEL, LEVEL_POW)
                .claim(CLAIM_SALT, salt)
                .claim(CLAIM_DIFFICULTY, difficulty)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getChallengeTtlSeconds())))
                .signWith(captchaSecretKey)
                .compact();
    }

    /**
     * Подписанный эмодзи-challenge на действие: задание, девять глифов и HMAC правильного ответа. Соль/сложность не
     * участвуют — уровень выбирается сервером при выдаче.
     */
    public String issueEmojiChallenge(String action, String challengeId, String prompt, List<String> cells,
            String answerHash) {
        requireAction(action);
        if (cells == null || cells.size() < CELLS_MIN || cells.size() > CELLS_MAX) {
            throw new IllegalArgumentException(
                    "Эмодзи-challenge требует prompt, cells (" + CELLS_MIN + ".." + CELLS_MAX + ") и answerHash");
        }
        if (isBlank(prompt) || isBlank(answerHash)) {
            throw new IllegalArgumentException(
                    "Эмодзи-challenge требует prompt, cells (" + CELLS_MIN + ".." + CELLS_MAX + ") и answerHash");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_TYPE, TYPE_CHALLENGE)
                .claim(CLAIM_ACTION, action)
                .claim(CLAIM_CHALLENGE_ID, challengeId)
                .claim(CLAIM_LEVEL, LEVEL_EMOJI)
                .claim(CLAIM_PROMPT, prompt)
                .claim(CLAIM_CELLS, String.join(CELLS_SEPARATOR, cells))
                .claim(CLAIM_ANSWER_HASH, answerHash)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getChallengeTtlSeconds())))
                .signWith(captchaSecretKey)
                .compact();
    }

    /** Разбор challenge: подпись, срок, тип и уровень; клиентское действие проверяется при выдаче токена. */
    public ChallengeClaims parseChallenge(String token) throws CaptchaException {
        Claims claims = parse(token);
        requireChallengeType(claims);
        String action = claims.get(CLAIM_ACTION, String.class);
        String challengeId = claims.get(CLAIM_CHALLENGE_ID, String.class);
        String level = claims.get(CLAIM_LEVEL, String.class);
        if (challengeId == null || !ACTIONS.contains(action)) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID);
        }
        Instant issuedAt = claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : Instant.EPOCH;
        if (LEVEL_EMOJI.equals(level)) {
            return parseEmojiClaims(claims, action, challengeId, issuedAt);
        }
        return parsePowClaims(claims, action, challengeId, issuedAt);
    }

    private static void requireChallengeType(Claims claims) throws CaptchaException {
        if (!TYPE_CHALLENGE.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.WRONG_TYPE);
        }
    }

    private static ChallengeClaims parseEmojiClaims(Claims claims, String action, String challengeId, Instant issuedAt)
            throws CaptchaException {
        String prompt = claims.get(CLAIM_PROMPT, String.class);
        String cellsRaw = claims.get(CLAIM_CELLS, String.class);
        String answerHash = claims.get(CLAIM_ANSWER_HASH, String.class);
        if (isBlank(prompt) || isBlank(cellsRaw) || isBlank(answerHash)) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID);
        }
        List<String> cells = List.of(cellsRaw.split("\\" + CELLS_SEPARATOR, -1));
        if (cells.size() < CELLS_MIN || cells.size() > CELLS_MAX) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID);
        }
        return new ChallengeClaims(challengeId, null, null, action, issuedAt, LEVEL_EMOJI, prompt, cells, answerHash);
    }

    private static ChallengeClaims parsePowClaims(Claims claims, String action, String challengeId, Instant issuedAt)
            throws CaptchaException {
        String salt = claims.get(CLAIM_SALT, String.class);
        Integer difficulty = claims.get(CLAIM_DIFFICULTY, Integer.class);
        if (salt == null || difficulty == null) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID);
        }
        return new ChallengeClaims(challengeId, salt, difficulty, action, issuedAt, LEVEL_POW, null, null, null);
    }

    /**
     * HMAC правильного ответа эмодзи-задания: {@code HMAC(id_challenge + ":" + индексы через запятую)}. Индексы
     * канонизируются (сортируются) до подписи — порядок в списке клиента не важен.
     */
    public String answerHash(String challengeId, List<Integer> answer) {
        if (challengeId == null || answer == null) {
            throw new IllegalArgumentException("answerHash требует challengeId и answer");
        }
        String canonical = answer.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(captchaSecretKey.getEncoded(), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((challengeId + ":" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Не удалось вычислить HMAC ответа эмодзи-задания", e);
        }
    }

    /** Подписанный captcha-токен на действие: время выдачи challenge для minimum-fill и уровень-источник решения. */
    public String issueCaptchaToken(String action, Instant challengeIssuedAt, String source) {
        requireAction(action);
        if (!LEVELS.contains(source)) {
            throw new IllegalArgumentException("Неизвестный источник токена капчи: " + source);
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_TYPE, TYPE_CAPTCHA)
                .claim(CLAIM_ACTION, action)
                .id(randomId())
                .claim(CLAIM_SOURCE, source)
                .claim(CLAIM_CHALLENGE_ISSUED_AT, Date.from(challengeIssuedAt))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getTokenTtlSeconds())))
                .signWith(captchaSecretKey)
                .compact();
    }

    /** Разбор captcha-токена: подпись, срок, тип, привязка к действию и уровень-источник решения. */
    public CaptchaTokenClaims parseCaptchaToken(String token, String expectedAction) throws CaptchaException {
        requireAction(expectedAction);
        Claims claims = parse(token);
        if (!TYPE_CAPTCHA.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.WRONG_TYPE);
        }
        if (!expectedAction.equals(claims.get(CLAIM_ACTION, String.class))) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.WRONG_ACTION);
        }
        String jti = claims.getId();
        String source = claims.get(CLAIM_SOURCE, String.class);
        Date challengeIssuedAt = claims.get(CLAIM_CHALLENGE_ISSUED_AT, Date.class);
        if (jti == null || jti.isBlank() || challengeIssuedAt == null || !LEVELS.contains(source)) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID);
        }
        return new CaptchaTokenClaims(jti, challengeIssuedAt.toInstant(), source);
    }

    private Claims parse(String token) throws CaptchaException {
        if (token == null || token.isBlank()) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.MISSING_TOKEN);
        }
        try {
            return Jwts.parser().verifyWith(captchaSecretKey).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.INVALID, e);
        }
    }

    /** Известно ли действие капчи (для валидации пользовательского ввода до выпуска). */
    public static boolean knownAction(String action) {
        return ACTIONS.contains(action);
    }

    private void requireAction(String action) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Неизвестное действие капчи: " + action);
        }
    }

    private void requirePowDifficulty(int difficulty) {
        if (difficulty < 1 || difficulty > MAX_POW_DIFFICULTY) {
            throw new IllegalArgumentException(
                    "Сложность PoW должна быть в диапазоне 1.." + MAX_POW_DIFFICULTY + ", получено " + difficulty);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Разобранный challenge: id и действие всегда; для уровня pow — соль и сложность, для emoji — задание, глифы и HMAC
     * ответа; level — уровень задания.
     */
    public record ChallengeClaims(String challengeId, String salt, Integer difficulty, String action, Instant issuedAt,
            String level,
            String prompt, List<String> cells, String answerHash) {
    }

    /** Разобранный captcha-токен: одноразовый id, время выдачи challenge и уровень-источник решения (pow или emoji). */
    public record CaptchaTokenClaims(String jti, Instant challengeIssuedAt, String source) {
    }
}

package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ukhanov.realhelpdesk.core.observability.AuthMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.ChallengeResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.SolveResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaReason;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.observability.CaptchaMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.EmojiTaskFactory.EmojiTask;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.utils.PowHash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Jwts;

@DisplayName("Оркестрация капчи: challenge (PoW/эмодзи), solve и verify токена")
class CaptchaServiceTest {

    private static final String ACTION = CaptchaTokenService.ACTION_REGISTER;
    private static final String IP = "1.2.3.4";
    private static final String IP_SAME_SUBNET = "1.2.3.5";
    private static final int DIFFICULTY = 4;
    private static final int DIFFICULTY_BONUS = 3;
    private static final int RISK_THRESHOLD = 2;

    private SecretKey key;
    private CaptchaTokenService tokenService;
    private CaptchaProperties properties;
    private CaptchaService service;
    private AuthMetrics authMetrics;
    private CaptchaMetrics captchaMetrics;
    private CaptchaRiskService riskService;
    private EmojiTaskFactory emojiTaskFactory;

    @BeforeEach
    void setUp() {
        key = new SecretKeySpec("test-only-captcha-hmac-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        properties = new CaptchaProperties();
        properties.setEnabled(true);
        properties.setPowDifficulty(DIFFICULTY);
        properties.setChallengeTtlSeconds(300);
        properties.setTokenTtlSeconds(300);
        properties.setMinFillSeconds(0);
        properties.setEmojiEnabled(true);
        properties.setRiskFailureThreshold(RISK_THRESHOLD);
        properties.setRiskFailureWindowSeconds(600);
        properties.setRiskElevatedDifficultyBonus(DIFFICULTY_BONUS);
        properties.setRiskSubnetOffenderThreshold(2);
        properties.setRiskSubnetPrefixV4(24);
        properties.setRiskSubnetPrefixV6(64);
        tokenService = new CaptchaTokenService(key, properties);
        authMetrics = Mockito.mock(AuthMetrics.class);
        captchaMetrics = Mockito.mock(CaptchaMetrics.class);
        riskService = new CaptchaRiskService(properties);
        emojiTaskFactory = Mockito.mock(EmojiTaskFactory.class);
        EmojiTask knownTask = new EmojiTask("Выберите все эмодзи категории «Транспорт»",
                List.of("🚗", "🐱", "🍎", "🍕", "💡", "🌳", "🐶", "📱", "🌻"), List.of(0, 3, 5));
        when(emojiTaskFactory.create()).thenReturn(knownTask);
        service = new CaptchaService(tokenService, new UsedTokenStore(300), properties, authMetrics, captchaMetrics,
                riskService,
                emojiTaskFactory);
    }

    // ────────────────────────────────────────────────
    // Challenge: PoW
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("challenge: тип pow, challengeId — подписанный JWT, соль/сложность совпадают с подписью")
    void challenge_registeredAction_returnsPowAssignment() throws Exception {

        // given
        // when
        Instant before = Instant.now();

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.type()).isEqualTo(ChallengeResponse.TYPE_POW);
        assertThat(response.params().salt()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(response.params().difficulty()).isEqualTo(DIFFICULTY);
        assertThat(response.expiresAt()).isBetween(before.plusSeconds(300), Instant.now().plusSeconds(305));

        CaptchaTokenService.ChallengeClaims claims = tokenService.parseChallenge(response.challengeId());
        assertThat(claims.challengeId()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(claims.salt()).isEqualTo(response.params().salt());
        assertThat(claims.difficulty()).isEqualTo(DIFFICULTY);
        assertThat(claims.action()).isEqualTo(ACTION);
        assertThat(claims.level()).isEqualTo(CaptchaTokenService.LEVEL_POW);
        verify(captchaMetrics).challengeIssued(CaptchaMetrics.TYPE_POW);
    }

    @Test
    @DisplayName("challenge с неизвестным действием — INVALID, метрика не растёт")
    void challenge_unknownAction_throwsInvalid() {

        // given / when / then
        assertThatThrownBy(() -> service.challenge("other", IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.INVALID);

        verifyNoInteractions(captchaMetrics);
    }

    // ────────────────────────────────────────────────
    // Challenge: эскалация на эмодзи
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Эскалация: провалы разных IP одной подсети дают эмодзи-задание (9 глифов)")
    void challenge_distributedSubnet_escalatesToEmoji() throws Exception {

        // given
        // when
        riskService.recordFailure(IP);
        riskService.recordFailure(IP_SAME_SUBNET);

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.type()).isEqualTo(ChallengeResponse.TYPE_EMOJI);
        assertThat(response.params().prompt()).isNotBlank();
        assertThat(response.params().cells()).hasSize(9).doesNotHaveDuplicates();
        assertThat(response.params().salt()).isNull();

        CaptchaTokenService.ChallengeClaims claims = tokenService.parseChallenge(response.challengeId());
        assertThat(claims.level()).isEqualTo(CaptchaTokenService.LEVEL_EMOJI);
        assertThat(claims.prompt()).isEqualTo(response.params().prompt());
        assertThat(claims.cells()).isEqualTo(response.params().cells());
        verify(captchaMetrics).challengeIssued(CaptchaMetrics.TYPE_EMOJI);
    }

    @Test
    @DisplayName("Мягкий риск не поднимает сложность выше максимума PoW (кламп 26)")
    void challenge_softRisk_clampsDifficultyToMax() throws CaptchaException {

        // given
        // when
        properties.setPowDifficulty(CaptchaTokenService.MAX_POW_DIFFICULTY - 2);
        properties.setRiskElevatedDifficultyBonus(5);
        riskService.recordFailure(IP);
        riskService.recordFailure(IP);

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.params().difficulty()).isEqualTo(CaptchaTokenService.MAX_POW_DIFFICULTY);
    }

    @Test
    @DisplayName("Мягкий риск одного IP (NAT) усложняет PoW, но эмодзи не выдаёт")
    void challenge_singleIpSoftRisk_elevatesPowDifficulty() throws Exception {

        // given
        // when
        riskService.recordFailure(IP);
        riskService.recordFailure(IP);

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.type()).isEqualTo(ChallengeResponse.TYPE_POW);
        assertThat(response.params().difficulty()).isEqualTo(DIFFICULTY + DIFFICULTY_BONUS);
        CaptchaTokenService.ChallengeClaims claims = tokenService.parseChallenge(response.challengeId());
        assertThat(claims.difficulty()).isEqualTo(DIFFICULTY + DIFFICULTY_BONUS);
    }

    @Test
    @DisplayName("Эскалация выключена флагом — распределённая подсеть всё равно получает PoW")
    void challenge_emojiDisabled_staysPow() throws CaptchaException {

        // given
        // when
        properties.setEmojiEnabled(false);
        riskService.recordFailure(IP);
        riskService.recordFailure(IP_SAME_SUBNET);

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.type()).isEqualTo(ChallengeResponse.TYPE_POW);
    }

    @Test
    @DisplayName("Провалы до порога не эскалируют")
    void challenge_belowThreshold_staysPow() throws CaptchaException {

        // given
        // when
        riskService.recordFailure(IP);

        ChallengeResponse response = service.challenge(ACTION, IP);

        // then
        assertThat(response.type()).isEqualTo(ChallengeResponse.TYPE_POW);
        assertThat(response.params().difficulty()).isEqualTo(DIFFICULTY);
    }

    // ────────────────────────────────────────────────
    // Solve: PoW
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("solve с верным nonce возвращает подписанный captcha-токен с источником pow")
    void solve_correctNonce_returnsCaptchaToken() throws Exception {

        // given
        // when
        ChallengeResponse challenge = service.challenge(ACTION, IP);
        String nonce = solve(challenge);

        SolveResponse response = service.solve(challenge.challengeId(), nonce, null, IP);

        CaptchaTokenService.CaptchaTokenClaims claims = tokenService.parseCaptchaToken(response.captchaToken(), ACTION);
        // then
        assertThat(claims.jti()).hasSize(32);
        assertThat(claims.challengeIssuedAt()).isNotNull();
        assertThat(claims.source()).isEqualTo(CaptchaTokenService.LEVEL_POW);
    }

    @Test
    @DisplayName("solve с неверным nonce — POW_FAILED, провалы учитываются в риске до порога эскалации")
    void solve_wrongNonce_recordsRiskUntilThreshold() throws CaptchaException {

        // given
        // when
        ChallengeResponse challenge = service.challenge(ACTION, IP);
        String wrongNonce = wrongNonce(challenge);

        // then
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), wrongNonce, null, IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.POW_FAILED);

        verify(authMetrics).record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_FAILURE);
        verify(captchaMetrics).verification(false, CaptchaReason.POW_FAILED, CaptchaMetrics.TYPE_POW);
        assertThat(riskService.riskLevel(IP)).as("один провал — ещё ниже порога")
                .isEqualTo(CaptchaRiskService.Risk.NONE);

        // второй провал (cid не сжигается при отказе — тот же challenge решается повторно) доводит мягкий риск до
        // порога 2
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), wrongNonce, null, IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.POW_FAILED);
        assertThat(riskService.riskLevel(IP)).as("два провала — мягкий риск достигнут")
                .isEqualTo(CaptchaRiskService.Risk.ELEVATED);
    }

    // ────────────────────────────────────────────────
    // Solve: эмодзи
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("solve эмодзи с верными индексами ячеек — токен с источником emoji")
    void solve_correctEmojiAnswer_returnsToken() throws Exception {

        // given
        // when
        ChallengeResponse challenge = escalatedEmojiChallenge();

        SolveResponse response = service.solve(challenge.challengeId(), null, List.of(0, 3, 5), IP);

        CaptchaTokenService.CaptchaTokenClaims claims = tokenService.parseCaptchaToken(response.captchaToken(), ACTION);
        // then
        assertThat(claims.source()).isEqualTo(CaptchaTokenService.LEVEL_EMOJI);
    }

    @Test
    @DisplayName("solve эмодзи с неверным набором ячеек — WRONG_ANSWER и провал в риске")
    void solve_wrongEmojiAnswer_returnsWrongAnswer() throws CaptchaException {

        // given
        // when
        ChallengeResponse challenge = escalatedEmojiChallenge();

        // then
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), null, List.of(1, 2), IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ANSWER);

        verify(captchaMetrics).verification(false, CaptchaReason.WRONG_ANSWER, CaptchaMetrics.TYPE_EMOJI);
    }

    @Test
    @DisplayName("solve эмодзи с пустым, выходящим за сетку или повторяющимся ответом — WRONG_ANSWER")
    void solve_invalidEmojiAnswer_returnsWrongAnswer() throws Exception {

        // given
        // when
        ChallengeResponse challenge = escalatedEmojiChallenge();

        // then
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), null, List.of(), IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ANSWER);
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), null, List.of(9), IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ANSWER);
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), null, List.of(0, 0), IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ANSWER);
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), null, null, IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ANSWER);
    }

    // ────────────────────────────────────────────────
    // Solve: общие отказы
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Повторное решение того же challenge — REPLAYED")
    void solve_sameChallengeTwice_returnsReplayed() throws Exception {

        // given
        // when
        ChallengeResponse challenge = service.challenge(ACTION, IP);
        String nonce = solve(challenge);
        service.solve(challenge.challengeId(), nonce, null, IP);

        // then
        assertThatThrownBy(() -> service.solve(challenge.challengeId(), nonce, null, IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.REPLAYED);
    }

    @Test
    @DisplayName("Подделанный challenge — INVALID, решение не принимается")
    void solve_tamperedChallenge_returnsInvalid() throws CaptchaException {

        // given
        // when
        ChallengeResponse challenge = service.challenge(ACTION, IP);
        String jwt = challenge.challengeId();
        String tampered = jwt.substring(0, jwt.length() - 2) + "aa";

        // then
        assertThatThrownBy(() -> service.solve(tampered, "0", null, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.INVALID);
    }

    @Test
    @DisplayName("Истёкший challenge — EXPIRED")
    void solve_expiredChallenge_returnsExpired() {

        // given
        // when
        Instant past = Instant.now().minusSeconds(600);
        String expired = Jwts.builder()
                .claim("typ", CaptchaTokenService.TYPE_CHALLENGE)
                .claim("act", ACTION)
                .claim("cid", "cid")
                .claim("lvl", CaptchaTokenService.LEVEL_POW)
                .claim("salt", "salt")
                .claim("dif", DIFFICULTY)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(300)))
                .signWith(key)
                .compact();

        // then
        assertThatThrownBy(() -> service.solve(expired, "0", null, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.EXPIRED);
    }

    // ────────────────────────────────────────────────
    // Verify
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("verify принимает свежий токен, пишет успех и сбрасывает риск IP")
    void verify_validToken_recordsSuccessAndResetsRisk() throws Exception {

        // given
        // when
        String token = tokenService.issueCaptchaToken(ACTION, Instant.now(), CaptchaTokenService.LEVEL_POW);
        riskService.recordFailure(IP);
        riskService.recordFailure(IP);
        // then
        assertThat(riskService.riskLevel(IP)).isEqualTo(CaptchaRiskService.Risk.ELEVATED);

        service.verify(token, ACTION, IP);

        verify(authMetrics).record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_SUCCESS);
        verify(captchaMetrics).verification(true, CaptchaReason.OK, CaptchaMetrics.TYPE_POW);
        assertThat(riskService.riskLevel(IP)).as("успех сбрасывает счётчик риска")
                .isEqualTo(CaptchaRiskService.Risk.NONE);
    }

    @Test
    @DisplayName("verify без токена — MISSING_TOKEN с фиксацией промаха")
    void verify_missingToken_returnsMissingToken() {

        // given / when / then
        assertThatThrownBy(() -> service.verify(null, ACTION, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.MISSING_TOKEN);

        verify(authMetrics).record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_FAILURE);
        verify(captchaMetrics).verification(false, CaptchaReason.MISSING_TOKEN, CaptchaMetrics.TYPE_UNKNOWN);
    }

    @Test
    @DisplayName("Токен чужого действия — WRONG_ACTION")
    void verify_tokenOfOtherAction_returnsWrongAction() {

        // given
        // when
        String token = tokenService.issueCaptchaToken(CaptchaTokenService.ACTION_EMAIL_CODE, Instant.now(),
                CaptchaTokenService.LEVEL_POW);

        // then
        assertThatThrownBy(() -> service.verify(token, ACTION, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.WRONG_ACTION);
    }

    @Test
    @DisplayName("Повторный предъявленный токен — REPLAYED")
    void verify_sameTokenTwice_returnsReplayed() throws Exception {

        // given
        // when
        String token = tokenService.issueCaptchaToken(ACTION, Instant.now(), CaptchaTokenService.LEVEL_POW);
        service.verify(token, ACTION, IP);

        // then
        assertThatThrownBy(() -> service.verify(token, ACTION, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.REPLAYED);
    }

    @Test
    @DisplayName("Отправка раньше minimum-fill — TOO_FAST с типом токена в метрике")
    void verify_submittedTooFast_returnsTooFast() {

        // given
        // when
        properties.setMinFillSeconds(5);
        String token = tokenService.issueCaptchaToken(ACTION, Instant.now().minusSeconds(1),
                CaptchaTokenService.LEVEL_EMOJI);

        // then
        assertThatThrownBy(() -> service.verify(token, ACTION, IP)).isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.TOO_FAST);

        verify(captchaMetrics).verification(false, CaptchaReason.TOO_FAST, CaptchaMetrics.TYPE_EMOJI);
    }

    @Test
    @DisplayName("Капча выключена — fail-open, даже без токена регистрация проходит")
    void verify_captchaDisabled_passes() throws CaptchaException {

        // given
        // when
        properties.setEnabled(false);

        service.verify(null, ACTION, IP);

        // then
        verifyNoInteractions(authMetrics);
    }

    // ────────────────────────────────────────────────
    // Honeypot (регистрация)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Заполненный honeypot — HONEYPOT, даже при выключенной капче; пробелы считаются пустым полем")
    void verifyRegistration_filledHoneypot_returnsHoneypot() throws CaptchaException {

        // given
        // when
        properties.setEnabled(false);

        service.verifyRegistration(null, "   ", IP);

        // then
        assertThatThrownBy(() -> service.verifyRegistration(null, "https://spam.example", IP))
                .isInstanceOf(CaptchaException.class)
                .extracting(e -> ((CaptchaException) e).getReason())
                .isEqualTo(CaptchaReason.HONEYPOT);
        verify(authMetrics).record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_FAILURE);
        verify(captchaMetrics).verification(false, CaptchaReason.HONEYPOT, CaptchaMetrics.TYPE_UNKNOWN);
    }

    @Test
    @DisplayName("Пустой honeypot — проверка уходит в обычный verify токена")
    void verifyRegistration_emptyHoneypot_delegatesToVerify() throws Exception {

        // given
        // when
        String token = tokenService.issueCaptchaToken(ACTION, Instant.now(), CaptchaTokenService.LEVEL_POW);

        service.verifyRegistration(token, "", IP);

        // then
        verify(captchaMetrics).verification(true, CaptchaReason.OK, CaptchaMetrics.TYPE_POW);
    }

    /** Эмодзи-challenge из замоканной фабрики (известный набор глифов и ответ) — по распределённой подсети. */
    private ChallengeResponse escalatedEmojiChallenge() throws CaptchaException {
        riskService.recordFailure(IP);
        riskService.recordFailure(IP_SAME_SUBNET);
        return service.challenge(ACTION, IP);
    }

    /** Детерминированное не-решение: на малой сложности случайная строка могла бы «пройти». */
    private String wrongNonce(ChallengeResponse challenge) {
        for (long i = 0; i < 100_000; i++) {
            String nonce = Long.toString(i);
            if (!PowHash.meets(challenge.params().salt(), nonce, challenge.params().difficulty())) {
                return nonce;
            }
        }
        throw new IllegalStateException("не-решение не найдено");
    }

    private String solve(ChallengeResponse challenge) {
        for (long i = 0; i < 100_000; i++) {
            String nonce = Long.toString(i);
            if (PowHash.meets(challenge.params().salt(), nonce, challenge.params().difficulty())) {
                return nonce;
            }
        }
        throw new IllegalStateException("nonce не найден");
    }
}

package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.observability.AuthMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.ChallengeResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.SolveResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaReason;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.observability.CaptchaMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaTokenService.CaptchaTokenClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaTokenService.ChallengeClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.EmojiTaskFactory.EmojiTask;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.utils.PowHash;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Оркестрация капчи: выдача challenge (PoW или эмодзи-задание при риске), приём решения с проверкой и проверка
 * captcha-токена на бизнес-операциях.
 *
 * <p>
 * Два уровня задания:
 * </p>
 * <ul>
 * <li><b>PoW</b> — базовый уровень: сервер подписывает соль и сложность, клиент подбирает {@code nonce}. Для IP с
 * мягким риском ({@link CaptchaRiskService.Risk#ELEVATED}) к сложности добавляется
 * {@code captcha.risk-elevated-difficulty-bonus} бит, но эмодзи не выдаётся — NAT-сеть с одним внешним адресом не
 * наказывается за провалы отдельных клиентов.</li>
 * <li><b>Эмодзи</b> — эскалация при {@link CaptchaRiskService.Risk#EMOJI} (распределённая атака: много разных IP одной
 * подсети) и включённом {@code captcha.emoji-enabled}: интерактивная сетка глифов, ответ сверяется по HMAC в подписи
 * challenge.</li>
 * </ul>
 *
 * <p>
 * Проверка токена (fail-open при выключенной капче) и honeypot живут здесь, чтобы контроллеры знали про капчу только
 * как про {@code verify(...)}. Причины отказа фиксируются в {@link CaptchaException#getReason()} и уходят в метрики.
 * Сам подписанный challenge — stateless JWT: сервер не хранит выданные задания, только израсходованные
 * ({@link UsedTokenStore}).
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class CaptchaService {

    private final CaptchaTokenService tokenService;
    private final UsedTokenStore usedTokenStore;
    private final CaptchaProperties properties;
    private final AuthMetrics authMetrics;
    private final CaptchaMetrics captchaMetrics;
    private final CaptchaRiskService riskService;
    private final EmojiTaskFactory emojiTaskFactory;

    /**
     * Новое задание: для IP с эмодзи-риском (и включённой эскалации) — эмодзи-задание, иначе PoW с солью и сложностью
     * (повышенной при мягком риске). Сам подписанный JWT возвращается в поле {@code challengeId} — solve принимает его
     * без серверного состояния.
     */
    public ChallengeResponse challenge(String action, String clientIp) throws CaptchaException {
        if (!CaptchaTokenService.knownAction(action)) {
            throw new CaptchaException("Неизвестное действие капчи", CaptchaReason.INVALID);
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.getChallengeTtlSeconds());
        CaptchaRiskService.Risk risk = riskService.riskLevel(clientIp);
        if (Boolean.TRUE.equals(properties.getEmojiEnabled()) && risk == CaptchaRiskService.Risk.EMOJI) {
            EmojiTask task = emojiTaskFactory.create();
            String challengeId = tokenService.randomId();
            String answerHash = tokenService.answerHash(challengeId, task.answer());
            String signedChallenge = tokenService.issueEmojiChallenge(action, challengeId, task.prompt(), task.cells(),
                    answerHash);
            captchaMetrics.challengeIssued(CaptchaMetrics.TYPE_EMOJI);
            logger.debug("Выдан emoji-challenge (эскалация по риску), action={}, challengeId={}", action, challengeId);
            return new ChallengeResponse(signedChallenge, ChallengeResponse.TYPE_EMOJI,
                    ChallengeResponse.Params.emoji(task.prompt(), task.cells()), expiresAt);
        }
        String challengeId = tokenService.randomId();
        String salt = tokenService.randomId();
        int difficulty = powDifficulty(risk);
        String signedChallenge = tokenService.issueChallenge(action, challengeId, salt, difficulty);
        captchaMetrics.challengeIssued(CaptchaMetrics.TYPE_POW);
        logger.debug("Выдан challenge, action={}, challengeId={}, difficulty={}", action, challengeId, difficulty);
        return new ChallengeResponse(signedChallenge, ChallengeResponse.TYPE_POW,
                ChallengeResponse.Params.pow(salt, difficulty),
                expiresAt);
    }

    /**
     * Сложность PoW: базовая, при мягком риске — плюс биты, но не выше {@link CaptchaTokenService#MAX_POW_DIFFICULTY}.
     */
    private int powDifficulty(CaptchaRiskService.Risk risk) {
        int base = properties.getPowDifficulty();
        if (risk != CaptchaRiskService.Risk.ELEVATED) {
            return base;
        }
        return Math.min(base + properties.getRiskElevatedDifficultyBonus(), CaptchaTokenService.MAX_POW_DIFFICULTY);
    }

    /**
     * Приём решения: подпись и срок challenge, неизрасходованность id, сам ответ (PoW-nonce или индексы эмодзи-ячеек).
     * Успех — подписанный captcha-токен на действие challenge с уровнем-источником решения.
     */
    public SolveResponse solve(String challengeJwt, String nonce, List<Integer> answer, String clientIp)
            throws CaptchaException {
        ChallengeClaims claims;
        try {
            claims = tokenService.parseChallenge(challengeJwt);
        } catch (CaptchaException e) {
            recordFailure(e.getReason(), CaptchaMetrics.TYPE_UNKNOWN, clientIp);
            throw e;
        }

        if (CaptchaTokenService.LEVEL_EMOJI.equals(claims.level())) {
            requireCorrectAnswer(claims, answer, clientIp);
        } else {
            requireCorrectPow(claims, nonce, clientIp);
        }
        if (!usedTokenStore.firstUse("c:" + claims.challengeId())) {
            recordFailure(CaptchaReason.REPLAYED, claims.level(), clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.REPLAYED);
        }

        String captchaToken = tokenService.issueCaptchaToken(claims.action(), claims.issuedAt(), claims.level());
        logger.debug("Решение капчи принято, challengeId={}, action={}, level={}", claims.challengeId(),
                claims.action(), claims.level());
        return new SolveResponse(captchaToken);
    }

    /**
     * Проверка captcha-токена на бизнес-операции: подпись, срок, тип, действие, минимальное время заполнения и
     * одноразовость. При выключенной капче пропускается (fail-open, чтобы разработка без PoW не вставала).
     */
    public void verify(String captchaToken, String action, String clientIp) throws CaptchaException {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return;
        }
        doVerify(captchaToken, action, clientIp);
    }

    /**
     * Проверка на регистрации: сначала honeypot (всегда, независимо от {@code captcha.enabled} — заполненное невидимое
     * поле однозначно бот), затем обычный verify токена.
     */
    public void verifyRegistration(String captchaToken, String honeypotValue, String clientIp) throws CaptchaException {
        if (honeypotValue != null && !honeypotValue.isBlank()) {
            recordFailure(CaptchaReason.HONEYPOT, CaptchaMetrics.TYPE_UNKNOWN, clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.HONEYPOT);
        }
        verify(captchaToken, CaptchaTokenService.ACTION_REGISTER, clientIp);
    }

    private void requireCorrectPow(ChallengeClaims claims, String nonce, String clientIp) throws CaptchaException {
        if (nonce == null || nonce.isBlank()) {
            recordFailure(CaptchaReason.POW_FAILED, CaptchaTokenService.LEVEL_POW, clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.POW_FAILED);
        }
        if (!PowHash.meets(claims.salt(), nonce, claims.difficulty())) {
            recordFailure(CaptchaReason.POW_FAILED, CaptchaTokenService.LEVEL_POW, clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.POW_FAILED);
        }
    }

    /**
     * Проверка ответа эмодзи-задания: индексы ячеек в пределах сетки, без повторов, и HMAC совпадает с подписанным в
     * challenge.
     */
    private void requireCorrectAnswer(ChallengeClaims claims, List<Integer> answer, String clientIp)
            throws CaptchaException {
        if (answer == null || answer.isEmpty() || answer.size() > claims.cells().size()) {
            rejectWrongAnswer(clientIp);
            return;
        }
        boolean hasDuplicate = answer.stream().distinct().count() != answer.size();
        boolean outOfRange = answer.stream()
                .anyMatch(index -> isOutOfRange(index, claims.cells().size()));
        boolean answerHashMatches = tokenService.answerHash(claims.challengeId(), answer).equals(claims.answerHash());
        if (hasDuplicate || outOfRange || !answerHashMatches) {
            rejectWrongAnswer(clientIp);
        }
    }

    private static boolean isOutOfRange(Integer index, int size) {
        return index == null || index < 0 || index >= size;
    }

    private void rejectWrongAnswer(String clientIp) throws CaptchaException {
        recordFailure(CaptchaReason.WRONG_ANSWER, CaptchaTokenService.LEVEL_EMOJI, clientIp);
        throw new CaptchaException("Провал прохождения капчи", CaptchaReason.WRONG_ANSWER);
    }

    private void doVerify(String captchaToken, String action, String clientIp) throws CaptchaException {
        CaptchaTokenClaims claims;
        try {
            claims = tokenService.parseCaptchaToken(captchaToken, action);
        } catch (CaptchaException e) {
            recordFailure(e.getReason(), CaptchaMetrics.TYPE_UNKNOWN, clientIp);
            throw e;
        }

        int minFill = properties.getMinFillSeconds();
        if (minFill > 0 && Instant.now().isBefore(claims.challengeIssuedAt().plusSeconds(minFill))) {
            recordFailure(CaptchaReason.TOO_FAST, claims.source(), clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.TOO_FAST);
        }
        if (!usedTokenStore.firstUse("t:" + claims.jti())) {
            recordFailure(CaptchaReason.REPLAYED, claims.source(), clientIp);
            throw new CaptchaException("Провал прохождения капчи", CaptchaReason.REPLAYED);
        }

        recordSuccess(claims.source(), clientIp);
        logger.debug("Captcha-токен принят, action={}, source={}", action, claims.source());
    }

    private void recordFailure(CaptchaReason reason, String type, String clientIp) {
        authMetrics.record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_FAILURE);
        captchaMetrics.verification(false, reason, type);
        riskService.recordFailure(clientIp);
    }

    private void recordSuccess(String type, String clientIp) {
        authMetrics.record(AuthMetrics.TYPE_CAPTCHA, AuthMetrics.RESULT_SUCCESS);
        captchaMetrics.verification(true, CaptchaReason.OK, type);
        riskService.reset(clientIp);
    }
}

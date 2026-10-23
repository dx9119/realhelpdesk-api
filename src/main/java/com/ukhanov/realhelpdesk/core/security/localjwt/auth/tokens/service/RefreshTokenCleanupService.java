package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.config.ConfigValidator;
import com.ukhanov.realhelpdesk.core.filter.LoggingFilter;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Ротация refresh-токенов: удаляет накопившиеся строки {@code jwt_tokens}. Отзыв (логаут, смена пароля, ротация при
 * входе) меняет только статус, а протухшие активные строки остаются, — поэтому без очистки таблица растёт
 * неограниченно.
 *
 * <p>
 * Два срока:
 * </p>
 * <ul>
 * <li>отозванные строки (статус не {@code ACTIVE}) старше {@code jwt.revoked-token-retention-seconds};</li>
 * <li>активные строки старше срока жизни refresh-токена ({@code jwt.refresh-token-expiration-seconds}, секунды) — токен
 * уже недействителен по {@code exp}.</li>
 * </ul>
 *
 * <p>
 * Удаление не монолитное: сначала оценивается объём ({@code COUNT} по индексу {@code created_at}), затем строки
 * удаляются батчами — каждый батч в своей короткой транзакции ({@link RefreshTokenCleanupExecutor}), между батчами
 * пауза. Размер батча адаптивный: вырос в пределах
 * {@code jwt.token-cleanup-batch-min-size}..{@code jwt.token-cleanup-batch-max-size} при быстром батче и уменьшился при
 * медленном относительно {@code jwt.token-cleanup-target-batch-ms}. Так очистка даёт ровную нагрузку без пиков.
 * </p>
 *
 * <p>
 * Расписание — {@code jwt.token-cleanup-cron} (6 полей, с секундами). Значения проверяются на старте.
 * </p>
 */
@Slf4j
@Service
public class RefreshTokenCleanupService {

    /** Технический контекст фонового запуска: у планировщика нет запроса, к которому привязать MDC. */
    private static final String SCHEDULER_REQUEST_ID = "scheduler-token-cleanup";
    private static final String SCHEDULER_URI = "scheduled:token-cleanup";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;
    private final RefreshTokenCleanupExecutor batchExecutor;
    private final JwtProperties jwtProperties;

    public RefreshTokenCleanupService(JwtRefreshTokenRepository jwtRefreshTokenRepository,
            RefreshTokenCleanupExecutor batchExecutor,
            JwtProperties jwtProperties) {
        this.jwtRefreshTokenRepository = jwtRefreshTokenRepository;
        this.batchExecutor = batchExecutor;
        this.jwtProperties = jwtProperties;
        ConfigValidator.requirePositive(jwtProperties.getRevokedTokenRetentionSeconds(),
                "jwt.revoked-token-retention-seconds");
        ConfigValidator.requirePositive(jwtProperties.getRefreshTokenExpirationSeconds(),
                "jwt.refresh-token-expiration-seconds");
        ConfigValidator.requireNonBlank(jwtProperties.getTokenCleanupCron(), "jwt.token-cleanup-cron");
        int batchMin = ConfigValidator.requirePositive(jwtProperties.getTokenCleanupBatchMinSize(),
                "jwt.token-cleanup-batch-min-size");
        ConfigValidator.requireAtLeast(jwtProperties.getTokenCleanupBatchMaxSize(), batchMin,
                "jwt.token-cleanup-batch-max-size");
        ConfigValidator.requirePositive(jwtProperties.getTokenCleanupTargetBatchMs(),
                "jwt.token-cleanup-target-batch-ms");
        ConfigValidator.requireAtLeast(jwtProperties.getTokenCleanupBatchPauseMs(), 0,
                "jwt.token-cleanup-batch-pause-ms");
    }

    @Scheduled(cron = "${jwt.token-cleanup-cron}")
    public void deleteExpiredTokens() {
        MDC.put(LoggingFilter.REQUEST_ID, SCHEDULER_REQUEST_ID);
        MDC.put(LoggingFilter.URI_REQUEST, SCHEDULER_URI);
        try {
            CleanupResult inactive = cleanRevokedTokens();
            CleanupResult active = cleanExpiredActiveTokens();

            int deleted = inactive.deleted() + active.deleted();
            if (deleted > 0) {
                // INFO, а не DEBUG: в prod должно быть видно, что ротация работает
                logger.info(
                        "Ротация refresh-токенов: удалено строк — отозванных {} ({} батчей), протухших {} ({} батчей)",
                        inactive.deleted(), inactive.batches(), active.deleted(), active.batches());
            } else {
                logger.debug("Ротация refresh-токенов: удалять нечего");
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Принудительная очистка отозванных строк (статус не {@code ACTIVE}) старше
     * {@code jwt.revoked-token-retention-seconds}. Тот же механизм, что и у ночного расписания: оценка объёма, затем
     * батчи с адаптивным размером и паузами. Возвращает число удалённых строк и батчей — вызывается операторским API и
     * переиспользуется в {@link #deleteExpiredTokens()}.
     */
    public CleanupResult cleanRevokedTokens() {
        Instant cutoff = Instant.now().minus(jwtProperties.getRevokedTokenRetentionSeconds(), ChronoUnit.SECONDS);
        long estimated = jwtRefreshTokenRepository.countByStatusNotAndCreatedAtBefore(TokenStatus.ACTIVE, cutoff);
        logger.info("Ротация refresh-токенов: отозванные старше {} с — оценка {}, стартовый батч {}",
                jwtProperties.getRevokedTokenRetentionSeconds(), estimated,
                jwtProperties.getTokenCleanupBatchMinSize());
        return purge(true, cutoff);
    }

    /** Протухшие активные строки старше срока жизни refresh-токена: токен уже недействителен по {@code exp}. */
    private CleanupResult cleanExpiredActiveTokens() {
        Instant cutoff = Instant.now().minus(jwtProperties.getRefreshTokenExpirationSeconds(), ChronoUnit.SECONDS);
        long estimated = jwtRefreshTokenRepository.countByStatusAndCreatedAtBefore(TokenStatus.ACTIVE, cutoff);
        logger.info("Ротация refresh-токенов: протухшие активные старше {} с — оценка {}, стартовый батч {}",
                jwtProperties.getRefreshTokenExpirationSeconds(), estimated,
                jwtProperties.getTokenCleanupBatchMinSize());
        return purge(false, cutoff);
    }

    private CleanupResult purge(boolean inactive, Instant cutoff) {
        int minBatch = jwtProperties.getTokenCleanupBatchMinSize();
        int maxBatch = jwtProperties.getTokenCleanupBatchMaxSize();
        int batchSize = minBatch;
        int total = 0;
        int batches = 0;
        while (true) {
            long startNanos = System.nanoTime();
            int deleted = inactive
                    ? batchExecutor.deleteInactiveBatch(cutoff, batchSize)
                    : batchExecutor.deleteActiveBatch(cutoff, batchSize);
            long elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
            if (deleted == 0) {
                break;
            }
            total += deleted;
            batches++;
            int requested = batchSize;
            batchSize = adaptBatchSize(batchSize, elapsedMs, jwtProperties.getTokenCleanupTargetBatchMs(), minBatch,
                    maxBatch);
            if (deleted < requested) {
                // Взяли меньше, чем просили: подходящих строк больше нет
                break;
            }
            if (!pause()) {
                logger.warn("Ротация refresh-токенов: пауза прервана, прогон остановлен на {} удалённых строках",
                        total);
                break;
            }
        }
        return new CleanupResult(total, batches);
    }

    /** Адаптивный размер батча: медленный батч уменьшается, быстрый (вдвое быстрее цели) растёт, границы — min..max. */
    static int adaptBatchSize(int current, long elapsedMs, int targetMs, int minBatch, int maxBatch) {
        if (elapsedMs > targetMs && current > minBatch) {
            return Math.max(minBatch, current / 2);
        }
        if (elapsedMs < targetMs / 2 && current < maxBatch) {
            return Math.min(maxBatch, current * 2);
        }
        return current;
    }

    /** Пауза между батчами; {@code false} — поток прерван, прогон нужно завершить. */
    private boolean pause() {
        int pauseMs = jwtProperties.getTokenCleanupBatchPauseMs();
        if (pauseMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(pauseMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Итог удаления одной группы: сколько строк удалено и за сколько батчей. */
    public record CleanupResult(int deleted, int batches) {
    }
}

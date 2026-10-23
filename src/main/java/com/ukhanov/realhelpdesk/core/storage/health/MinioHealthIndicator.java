package com.ukhanov.realhelpdesk.core.storage.health;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.storage.config.MinioProperties;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Готовность хранилища вложений: readiness падает, когда MinIO недоступен или бакет пропал. Проверка — bucketExists по
 * одному бакету, без записи файлов; таймаут соединения задан на клиенте (MinioClientConfig), результат кэшируется на
 * несколько секунд, чтобы опрос health не превращался в нагрузку на MinIO.
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnEnabledHealthIndicator("minio")
public class MinioHealthIndicator implements HealthIndicator {

    /** Имя бина в actuator: minio → /actuator/health показывает блок "minio". */
    public static final String NAME = "minio";

    /** Свежий результат держим 5 секунд: оркестратор и Prometheus опрашивают health чаще, чем нужно проверять MinIO. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    private volatile Health cachedHealth;
    private volatile Instant checkedAt;

    @Override
    public Health health() {
        Health cached = cachedHealth;
        if (cached != null && checkedAt.plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        Health health = doCheck();
        cachedHealth = health;
        checkedAt = Instant.now();
        return health;
    }

    private Health doCheck() {
        Objects.requireNonNull(minioClient, "MinioClient не должен быть null");
        try {
            boolean exists = minioClient
                    .bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (exists) {
                return Health.up().withDetail("bucket", properties.getBucket()).build();
            }
            // бакет создаётся при первом обращении приложения — его отсутствие ещё не сбой
            return Health.up()
                    .withDetail("bucket", properties.getBucket())
                    .withDetail("state", "not created yet")
                    .build();
        } catch (ErrorResponseException | InsufficientDataException | InternalException | InvalidResponseException
                | InvalidKeyException | IOException | NoSuchAlgorithmException | ServerException
                | XmlParserException e) {
            logger.warn("MinIO недоступен для health-проверки: {}", e.getMessage());
            return Health.down(e).withDetail("endpoint", properties.getEndpoint()).build();
        }
    }
}

package com.ukhanov.realhelpdesk;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Базовый класс интеграционных тестов (*IT, failsafe): PostgreSQL 18 и MinIO в Testcontainers.
 *
 * <p>
 * Контейнеры — статические поля: на прогон JVM их по одному, повторного старта не будет ни при кэшировании контекстов
 * Spring, ни при загрузке тест-классов разными classloader'ами (поле живёт в базовом классе, который грузится один раз
 * на classloader). Остановку при выходе из JVM делает Ryuk.
 * </p>
 *
 * <p>
 * Подключение к PostgreSQL — через {@link ServiceConnection}: Boot публикует datasource из контейнера сам, ручной
 * {@code @DynamicPropertySource} с URL не нужен. Все контексты работают с одной базой контейнера, поэтому схему создаёт
 * Liquibase ({@code spring.liquibase.*} в {@code application-test.properties}), а Hibernate её только проверяет
 * ({@code ddl-auto=validate}) — расхождение миграций с entity валит IT.
 * </p>
 *
 * <p>
 * MinIO подключается явной привязкой свойств: Boot не знает {@code MinioConnectionDetails}, поэтому
 * {@code @ServiceConnection} для него недоступен. Адрес и учётные данные указывают на контейнер — тесты файлов работают
 * с реальным S3-совместимым хранилищем.
 * </p>
 *
 * <p>
 * Unit-тесты ({@code make test}) контейнеров не поднимают и этот класс не используют. Для запуска IT нужен Docker.
 * </p>
 */
public abstract class AbstractIntegrationTest {

    private static final String MINIO_IMAGE = "pgsty/silo:RELEASE.2026-09-16T00-00-00Z";
    private static final String MINIO_USER = "test-access";
    private static final String MINIO_PASSWORD = "test-secret";
    private static final String MINIO_BUCKET = "test-bucket";
    private static final int MINIO_PORT = 9000;

    /** Образ совпадает с infrastructure/docker-compose.yaml (профиль infra). */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withCommand("server /data")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(MINIO_PORT));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_PORT));
        registry.add("minio.access-key", () -> MINIO_USER);
        registry.add("minio.secret-key", () -> MINIO_PASSWORD);
        registry.add("minio.bucket", () -> MINIO_BUCKET);
    }
}

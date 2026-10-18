package com.ukhanov.realhelpdesk;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Базовый класс интеграционных тестов (*IT, failsafe) на Testcontainers PostgreSQL 18.
 *
 * <p>
 * Один контейнер на весь прогон JVM. Failsafe может грузить тест-классы разными classloader'ами, поэтому старт контейнера и его
 * JDBC-параметры делятся через system properties ({@code it.pg.*}): повторная инициализация в другом classloader'е не поднимает второй
 * контейнер и не теряет URL. Остановку при выходе из JVM делает Ryuk. Каждый Spring-контекст получает собственную базу данных
 * ({@code it_<hash>}) — схемы тестов не пересекаются. Структура создаётся Hibernate ({@code ddl-auto=create-drop}): Liquibase в слайс-тесты
 * не входит, а для проверки SQL/диалекта достаточно entity-схемы. Миграции Liquibase проверяются отдельно — на поднятом dev-стеке
 * ({@code make up}) и в e2e.
 * </p>
 *
 * <p>
 * Unit-тесты ({@code make test}) остаются на H2 и этот класс не используют. Для запуска IT нужен Docker.
 * </p>
 */
public abstract class PostgresIntegrationTest {

    private static final String PROP_BASE_URL = "it.pg.baseurl";
    private static final String PROP_USER = "it.pg.user";
    private static final String PROP_PASS = "it.pg.pass";

    static {
        if (System.getProperty(PROP_BASE_URL) == null) {
            // Образ совпадает с infrastructure/docker-compose.yaml (профиль infra)
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");
            postgres.start();
            System.setProperty(PROP_BASE_URL, postgres.getJdbcUrl());
            System.setProperty(PROP_USER, postgres.getUsername());
            System.setProperty(PROP_PASS, postgres.getPassword());
        }
    }

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        String baseUrl = System.getProperty(PROP_BASE_URL);
        String user = System.getProperty(PROP_USER);
        String pass = System.getProperty(PROP_PASS);
        String dbName = "it_" + Math.abs(UUID.randomUUID().hashCode());
        createDatabase(baseUrl, user, pass, dbName);
        String defaultDb = "/" + (baseUrl.substring(baseUrl.lastIndexOf('/') + 1)).split("\\?")[0];
        String url = baseUrl.replace(defaultDb, "/" + dbName);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        // PostgreSQL не embedded: без явного create-drop схема не появится
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static void createDatabase(String baseUrl, String user, String pass, String dbName) {
        try (Connection connection = DriverManager.getConnection(baseUrl, user, pass)) {
            connection.createStatement().execute("CREATE DATABASE " + dbName);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать тестовую БД " + dbName, e);
        }
    }
}

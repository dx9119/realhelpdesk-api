package com.ukhanov.realhelpdesk.core.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт конфигурации приложения: {@code application.properties} и {@code application-domains.properties} (домены) — только схема из
 * обязательных {@code ${VAR}}, а значения лежат в двух местах: {@code infrastructure/.env.example} (шаблон рабочего
 * {@code infrastructure/.env} — весь несекретный набор — продукт, окружение, инфраструктура) и {@code services.app.environment} в
 * {@code infrastructure/docker-compose.yaml} (секреты через {@code ${VAR}} из {@code infrastructure/.env} и динамические ключи с дефолтами
 * {@code ${VAR:-...}}).
 *
 * <p>
 * Файлы читаются напрямую по пути {@code src/main/resources/...}, а не с classpath, поэтому на результат не влияет даже
 * {@code src/test/resources/application.properties}, который перекрывает main-конфиг в тестовом класслоаде.
 * </p>
 */
@DisplayName("Конфигурация: схема application*.properties, infrastructure/docker-compose.yaml и infrastructure/.env.example")
class ApplicationConfigSchemaTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path APPLICATION_DOMAINS_PROPERTIES = Path.of("src", "main", "resources", "application-domains.properties");
    private static final Path DOCKER_COMPOSE = Path.of("infrastructure", "docker-compose.yaml");
    private static final Path ENV_EXAMPLE = Path.of("infrastructure", ".env.example");
    /** Ровно ${VAR}: без дефолта после двоеточия и без литерала до/после. */
    private static final Pattern SCHEMA_VALUE = Pattern.compile("\\$\\{[A-Z_][A-Z0-9_]*}");
    private static final Pattern SCHEMA_VARIABLE = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");

    /**
     * Ключи, которым позволено содержать литерал: {@code spring.config.import} подключает application-domains.properties — значение это
     * адрес файла, а не переменная окружения. {@code spring.jpa.hibernate.ddl-auto} закреплён в коде (validate): режим, изменяющий схему,
     * нельзя оставить на откуп окружения — им управляют только миграции Liquibase; исключение отдельно охраняется
     * {@link #ddlAuto_pinnedToValidate_neverDestructive()}. {@code server.ssl.enabled=${SSL_ENABLED:true}} — HTTPS включён по умолчанию
     * (контейнер/рабочий контур), дефолт после двоеточия позволяет выключить его инлайном (IDEA-запуск: SSL_ENABLED=false), не добавляя
     * ключ в infrastructure/.env. Остальные — константы, одинаковые для всех окружений: пул Hikari, батчи Hibernate, graceful shutdown и
     * actuator (их меняют правкой файла с ревью, а не подменой env).
     */
    private static final Set<String> NON_SCHEMA_KEYS = Set.of("spring.config.import", "spring.jpa.hibernate.ddl-auto", "server.ssl.enabled",
            // Пул соединений
            "spring.datasource.hikari.maximum-pool-size", "spring.datasource.hikari.minimum-idle",
            "spring.datasource.hikari.connection-timeout", "spring.datasource.hikari.max-lifetime",
            "spring.datasource.hikari.leak-detection-threshold",
            // Батчи Hibernate
            "spring.jpa.properties.hibernate.jdbc.batch_size", "spring.jpa.properties.hibernate.order_inserts",
            "spring.jpa.properties.hibernate.order_updates", "spring.jpa.properties.hibernate.default_batch_fetch_size",
            // База данных: имя пуля и порог медленных запросов
            "spring.datasource.hikari.pool-name", "spring.jpa.properties.hibernate.log_slow_query",
            // Корректное завершение (long polling + @Scheduled)
            "server.shutdown", "spring.lifecycle.timeout-per-shutdown-phase", "spring.task.scheduling.shutdown.await-termination",
            // Трассировка
            "management.tracing.sampling.probability", "management.opentelemetry.resource-attributes.service.name",
            "management.opentelemetry.resource-attributes.service.version",
            "management.opentelemetry.resource-attributes.deployment.environment", "management.opentelemetry.tracing.export.otlp.endpoint",
            // OTLP-транспорт закреплён: Boot 4 шлёт http/protobuf (порт 4318 Collector'а);
            // смена на grpc — осознанная правка с ревью, не подмена env
            "management.opentelemetry.tracing.export.otlp.transport",
            // OTLP-метрики выключены: метрики идут Prometheus scrape через alloy → Mimir,
            // без флага starter-opentelemetry пушит их на localhost:4318 (connection refused)
            "management.otlp.metrics.export.enabled",
            // Actuator / метрики
            "management.endpoints.web.exposure.include", "management.endpoint.health.probes.enabled",
            "management.endpoint.health.show-details", "management.endpoint.health.group.liveness.include",
            "management.endpoint.health.group.readiness.include",
            "management.metrics.distribution.percentiles-histogram.http.server.requests",
            "management.metrics.distribution.slo.http.server.requests", "management.prometheus.metrics.export.enabled",
            "management.metrics.tags.application", "management.metrics.tags.env", "management.metrics.web.server.max-uri-tags",
            // Отключение неиспользуемых семейств метрик
            "management.metrics.enable.tomcat", "management.metrics.enable.hibernate.cache", "management.metrics.enable.hibernate.entities",
            "management.metrics.enable.hibernate.collections", "management.metrics.enable.spring.security.filterchains",
            "management.metrics.enable.spring.security.http.secured.requests", "management.metrics.enable.spring.security.authorizations",
            "management.metrics.enable.spring.data.repository", "management.server.port", "management.server.address",
            "management.server.ssl.enabled", "observability.prometheus.auth.enabled", "observability.prometheus.auth.username",
            "observability.prometheus.auth.password",
            // Почта: health-группа вне readiness (таймауты SMTP — env-управляемые ключи схемы)
            "management.endpoint.health.group.smtp.include");

    /**
     * Конфигурация среды: файлы схемы и места со значениями. В них не должно появиться деструктивного режима ddl-auto — иначе рестарт
     * уничтожит данные. Тестовые конфиги и {@code @DataJpaTest(properties=...)} не входят: там схему создаёт H2, это не прод.
     */
    private static final List<Path> ENVIRONMENT_FILES = List.of(Path.of("src", "main", "resources", "application.properties"),
            Path.of("src", "main", "resources", "application-domains.properties"),
            Path.of("src", "main", "resources", "application-dev.properties"),
            Path.of("src", "main", "resources", "application-host.properties"),
            Path.of("src", "main", "resources", "application-prod.properties"), DOCKER_COMPOSE, ENV_EXAMPLE);

    /** Любой ddl-auto в конфигурации среды: имя ключа и режим после {@code =}/{@code :}, кавычки опциональны. */
    private static final Pattern DDL_AUTO_ASSIGNMENT = Pattern.compile("(?i)ddl[-_]auto\\s*[=:]\\s*\"?([a-z-]+)\"?");

    /**
     * Ключи {@code services.app.environment} и {@code infrastructure/.env.example}, которых нет в схеме приложения. {@code JAVA_} —
     * JVM-флаги, переопределяющие {@code ENV} образа (sec.md §7.8); {@code INFRA} — инфраструктурные переменные compose (БД), которые нужны
     * контейнерам, но не приложению. {@code KC_}/{@code KEYCLOAK_} — контур Keycloak: ключи читает профильный
     * {@code application-keycloak.properties} (он не входит в схему — базовый конфиг обязан работать без Keycloak) и сервис
     * {@code keycloak} профиля dev; исключение по префиксу, потому что набор ключей растёт вместе с версией Keycloak.
     */
    private static final Set<String> NON_SCHEMA_ENV_PREFIXES = Set.of("JAVA_", "KC_", "KEYCLOAK_");

    private static final Set<String> NON_SCHEMA_ENV_KEYS = Set.of("POSTGRES_USER", "POSTGRES_DB", "PORT");

    /**
     * Секреты в шаблоне {@code infrastructure/.env.example}: он содержит и базовые значения, и пустые строки секретов — поэтому при сверке
     * со схемой они исключаются (место секретов в контракте — {@code services.app.environment} compose-файла).
     * {@code KEYCLOAK_ADMIN_PASSWORD} — пароль начального администратора Keycloak: значение задаёт хост, а в контейнер его передаёт
     * {@code services.keycloak.environment} ({@code ${KEYCLOAK_ADMIN_PASSWORD:?...}}), как и остальные секреты.
     * {@code KEYCLOAK_DB_PASSWORD} — пароль отдельного кластера PostgreSQL для Keycloak.
     */
    private static final Set<String> ENV_EXAMPLE_SECRET_KEYS = Set.of("JWT_SECRET", "CAPTCHA_HMAC_SECRET", "DB_PASSWORD", "MAIL_PASSWORD",
            "KEY_STORE_PASS", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD", "METRICS_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
            "KEYCLOAK_DB_PASSWORD");

    @Test
    @DisplayName("application*.properties — чистая схема: только обязательные ${VAR}, без дефолтов и литералов")
    void applicationProperties_allValuesAreVariables_returnsPureSchema() throws Exception {

        // given
        // when
        Properties schema = loadSchema();

        List<String> notSchema = schema.stringPropertyNames().stream().sorted().filter(name -> !NON_SCHEMA_KEYS.contains(name))
                .filter(name -> !SCHEMA_VALUE.matcher(schema.getProperty(name)).matches())
                .map(name -> name + "=" + schema.getProperty(name)).toList();

        // then
        assertThat(schema.stringPropertyNames()).as("схема должна содержать свойства").isNotEmpty();
        assertThat(notSchema).as("значения вне вида ${VAR} (дефолт или литерал в коде)").isEmpty();
    }

    @Test
    @DisplayName("Переменные схемы и ключи infrastructure/docker-compose.yaml + infrastructure/.env.example совпадают один в один")
    void schemaVariables_declaredInComposeOrEnvExample_noUnresolvedVariables() throws Exception {

        // given
        // when
        Properties schema = loadSchema();

        Set<String> schemaVariables = schema.stringPropertyNames().stream().map(schema::getProperty)
                .flatMap(value -> variables(value).stream()).collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> composeKeys = composeAppEnvironment();
        Set<String> envExampleKeys = appKeysOfEnvExample();

        // then
        assertThat(composeKeys.stream().filter(envExampleKeys::contains).toList())
                .as("одно значение может быть задано только в одном месте: infrastructure/.env.example или services.app.environment")
                .isEmpty();

        Set<String> declaredValues = new LinkedHashSet<>(composeKeys);
        declaredValues.addAll(envExampleKeys);
        assertThat(declaredValues).as("схема application.properties + application-domains.properties и ключи compose + .env.example")
                .containsExactlyInAnyOrderElementsOf(schemaVariables);
    }

    @Test
    @DisplayName("ddl-auto закреплён в коде: validate, деструктивный режим не появится в конфигурации среды")
    void ddlAuto_pinnedToValidate_neverDestructive() throws Exception {

        // given
        // when
        Properties schema = loadSchema();

        // then
        assertThat(schema.getProperty("spring.jpa.hibernate.ddl-auto")).as("режим схемы задан в коде, а не в окружении")
                .isEqualTo("validate");

        assertThat(composeAppEnvironment()).as("переменной ddl-auto в infrastructure/docker-compose.yaml быть не должно")
                .doesNotContain("JPA_DDL_AUTO");
        assertThat(appKeysOfEnvExample()).as("переменной ddl-auto в infrastructure/.env.example быть не должно")
                .doesNotContain("JPA_DDL_AUTO");

        for (Path file : ENVIRONMENT_FILES) {
            assertThat(Files.exists(file)).as(file.toString()).isTrue();
            String content = Files.readString(file, StandardCharsets.UTF_8);

            Matcher assignment = DDL_AUTO_ASSIGNMENT.matcher(content);
            while (assignment.find()) {
                assertThat(assignment.group(1)).as(file.toString() + ": режим ddl-auto").isEqualTo("validate");
            }

            assertThat(content).as(file.toString()).doesNotContain("create-drop").doesNotContain("drop-create");
        }
    }

    /** Оба файла схемы: основной конфиг и домены (spring.config.import). Дубли ключей между ними — ошибка. */
    private Properties loadSchema() throws Exception {
        Properties properties = new Properties();
        for (Path file : List.of(APPLICATION_PROPERTIES, APPLICATION_DOMAINS_PROPERTIES)) {
            assertThat(Files.exists(file)).as(file.toString()).isTrue();
            Properties part = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                part.load(reader);
            }
            Set<String> duplicates = new LinkedHashSet<>(part.stringPropertyNames());
            duplicates.retainAll(properties.stringPropertyNames());
            assertThat(duplicates).as("ключ объявлен в обоих файлах схемы: " + file).isEmpty();
            properties.putAll(part);
        }
        return properties;
    }

    private Set<String> variables(String value) {
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = SCHEMA_VARIABLE.matcher(value);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    /**
     * Ключи {@code services.app.environment} — секреты (ссылки на переменные хоста) и динамические ключи с дефолтами интерполяции
     * (наблюдаемость/нагрузка/прокси). JVM-флаги туда не входят.
     */
    @SuppressWarnings("unchecked")
    private Set<String> composeAppEnvironment() throws Exception {
        assertThat(Files.exists(DOCKER_COMPOSE)).as(DOCKER_COMPOSE.toString()).isTrue();
        try (var reader = Files.newBufferedReader(DOCKER_COMPOSE, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(reader);
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            Map<String, Object> app = (Map<String, Object>) services.get("app");
            Map<String, Object> environment = (Map<String, Object>) app.get("environment");

            assertThat(environment).as("services.app.environment в infrastructure/docker-compose.yaml").isNotNull();
            return environment.keySet().stream().filter(key -> NON_SCHEMA_ENV_PREFIXES.stream().noneMatch(key::startsWith))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /**
     * Ключи приложения в {@code infrastructure/.env.example}: только имя до первого {@code =}, комментарии и пустые строки пропускаются.
     * Значения здесь не проверяются — их смысл зависит от ключа, а не от формы. JVM-флаги, инфраструктурные ключи compose и секреты (их
     * место в {@code services.app.environment}) из контракта исключаются.
     */
    private Set<String> appKeysOfEnvExample() throws Exception {
        assertThat(Files.exists(ENV_EXAMPLE)).as(ENV_EXAMPLE.toString()).isTrue();
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ENV_EXAMPLE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            assertThat(separator).as("строка без = : " + line).isPositive();
            keys.add(trimmed.substring(0, separator).trim());
        }
        assertThat(keys).as(ENV_EXAMPLE.toString()).isNotEmpty();
        assertThat(keys).as("шаблон .env.example содержит все секреты").containsAll(ENV_EXAMPLE_SECRET_KEYS);
        return keys.stream().filter(key -> NON_SCHEMA_ENV_PREFIXES.stream().noneMatch(key::startsWith))
                .filter(key -> !NON_SCHEMA_ENV_KEYS.contains(key)).filter(key -> !ENV_EXAMPLE_SECRET_KEYS.contains(key))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

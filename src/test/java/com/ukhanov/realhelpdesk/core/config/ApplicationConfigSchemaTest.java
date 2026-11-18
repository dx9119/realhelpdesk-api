package com.ukhanov.realhelpdesk.core.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт конфигурации приложения: {@code application.properties} и {@code application-domains.properties} (домены) —
 * обязательные переменные окружения вида {@code ${VAR}} или {@code ${VAR:default}}. Значения по умолчанию задаются в
 * properties; секреты и специфичные для среды ключи (MAIL_HOST, CORS_ALLOWED_ORIGINS, DB_URL, MINIO_ENDPOINT) — без
 * дефолтов, чтобы приложение валилось при отсутствии. Приложение запускается на хосте, поэтому единственное место
 * значений — {@code infrastructure/.env.dev.example} (шаблон, из которого {@code make env} делает
 * {@code infrastructure/.env}); в Docker-контейнере приложение не живёт и своего окружения не имеет.
 *
 * <p>
 * Файлы читаются напрямую по пути {@code src/main/resources/...}, а не с classpath, поэтому на результат не влияет даже
 * {@code src/test/resources/application.properties}, который перекрывает main-конфиг в тестовом класслоаде.
 * </p>
 */
@DisplayName("Конфигурация: схема application*.properties и infrastructure/.env.dev.example")
class ApplicationConfigSchemaTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path APPLICATION_DOMAINS_PROPERTIES = Path.of("src", "main", "resources",
            "application-domains.properties");
    private static final Path ENV_DEV_EXAMPLE = Path.of("infrastructure", ".env.dev.example");
    /** Ровно ${VAR} или ${VAR:default} — без литерала до/после. Имя переменной — без дефолта. */
    private static final Pattern SCHEMA_VALUE = Pattern.compile("\\$\\{[A-Z_][A-Z0-9_]*(?::[^}]*)?}");
    /** ${VAR:default} — переменная с разумным дефолтом, не обязана быть в *.env.example / compose. */
    private static final Pattern SCHEMA_VALUE_WITH_DEFAULT = Pattern.compile("\\$\\{[A-Z_][A-Z0-9_]+:[^}]*}");
    private static final Pattern SCHEMA_VARIABLE = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)");

    /**
     * Ключи, которым позволено содержать литерал: {@code spring.config.import} подключает
     * application-domains.properties — значение это адрес файла, а не переменная окружения.
     * {@code spring.jpa.hibernate.ddl-auto} закреплён в коде (validate): режим, изменяющий схему, нельзя оставить на
     * откуп окружения — им управляют только миграции Liquibase; исключение отдельно охраняется
     * {@link #ddlAuto_pinnedToValidate_neverDestructive()}. Остальные — константы, одинаковые для всех окружений: пул
     * Hikari, батчи Hibernate, graceful shutdown и actuator (их меняют правкой файла с ревью, а не подменой env).
     */
    private static final Set<String> NON_SCHEMA_KEYS = Set.of("spring.config.import", "spring.jpa.hibernate.ddl-auto",
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
            "server.shutdown", "spring.lifecycle.timeout-per-shutdown-phase",
            "spring.task.scheduling.shutdown.await-termination",
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
            "management.metrics.tags.application", "management.metrics.tags.env",
            "management.metrics.web.server.max-uri-tags",
            // Отключение неиспользуемых семейств метрик
            "management.metrics.enable.tomcat", "management.metrics.enable.hibernate.cache",
            "management.metrics.enable.hibernate.entities",
            "management.metrics.enable.hibernate.collections", "management.metrics.enable.spring.security.filterchains",
            "management.metrics.enable.spring.security.http.secured.requests",
            "management.metrics.enable.spring.security.authorizations",
            "management.metrics.enable.spring.data.repository",
            "observability.prometheus.auth.enabled",
            // Почта: health-группа вне readiness (таймауты SMTP — env-управляемые ключи схемы)
            "management.endpoint.health.group.smtp.include");

    /**
     * Конфигурация среды: файлы схемы и места со значениями. В них не должно появиться деструктивного режима ddl-auto —
     * иначе рестарт уничтожит данные. Тестовые конфиги и {@code @DataJpaTest(properties=...)} не входят: там схему
     * создаёт H2, это не рабочая база.
     */
    private static final List<Path> ENVIRONMENT_FILES = List.of(APPLICATION_PROPERTIES, APPLICATION_DOMAINS_PROPERTIES,
            Path.of("src", "main", "resources", "application-dev.properties"),
            Path.of("src", "main", "resources", "application-host.properties"), ENV_DEV_EXAMPLE);

    /** Любой ddl-auto в конфигурации среды: имя ключа и режим после {@code =}/{@code :}, кавычки опциональны. */
    private static final Pattern DDL_AUTO_ASSIGNMENT = Pattern.compile("(?i)ddl[-_]auto\\s*[=:]\\s*\"?([a-z-]+)\"?");

    /**
     * Ключи {@code infrastructure/.env.dev.example}, которых нет в схеме приложения. {@code JAVA_} — JVM-флаги запуска
     * (heap dump, JFR, JMX, -Xmx), их читает JVM, а не Spring. {@code KC_}/{@code KEYCLOAK_} — настройки самого сервиса
     * Keycloak (профиль local-tools): {@code KC_HOSTNAME}, креды администратора и БД realm'а приложение не читает, а
     * набор ключей растёт вместе с версией Keycloak.
     */
    private static final Set<String> NON_SCHEMA_ENV_PREFIXES = Set.of("JAVA_", "KC_", "KEYCLOAK_");

    private static final Set<String> NON_SCHEMA_ENV_KEYS = Set.of("POSTGRES_USER", "POSTGRES_DB", "PORT");

    /**
     * Переменные без дефолта, которые объявляет не шаблон {@code .env}, а механизм запуска.
     * {@code SPRING_PROFILES_ACTIVE} задаёт {@code scripts/dev/run-local.sh} (контур из {@code RUN_PROFILES} плюс
     * {@code host}) и run-конфигурация IDEA: в {@code infrastructure/.env} его быть не должно — загрузка файла
     * выполняется после и перезаписала бы контур, выбранный make-целью.
     */
    private static final Set<String> RUNTIME_SET_VARIABLES = Set.of("SPRING_PROFILES_ACTIVE");

    /**
     * Секреты в {@code infrastructure/.env.dev.example}: они содержат и базовые значения, и пустые строки секретов —
     * поэтому при сверке со схемой они исключаются (их место в контракте — обязательность, а не значение).
     */
    private static final Set<String> ENV_EXAMPLE_SECRET_KEYS = Set.of("CAPTCHA_HMAC_SECRET", "DB_PASSWORD",
            "MAIL_PASSWORD", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD", "METRICS_PASSWORD");

    /**
     * Секреты локального Keycloak: сервис keycloak живёт в профиле local-tools, его креды нужны только локальному
     * стенду.
     */
    private static final Set<String> DEV_ONLY_SECRET_KEYS = Set.of("KEYCLOAK_ADMIN_PASSWORD", "KEYCLOAK_DB_PASSWORD");

    @Test
    @DisplayName("application*.properties — чистая схема: только обязательные ${VAR}, без дефолтов и литералов")
    void applicationProperties_allValuesAreVariables_returnsPureSchema() throws Exception {

        // given
        // when
        Properties schema = loadSchema();

        List<String> notSchema = schema.stringPropertyNames()
                .stream()
                .sorted()
                .filter(name -> !NON_SCHEMA_KEYS.contains(name))
                .filter(name -> !SCHEMA_VALUE.matcher(schema.getProperty(name)).matches())
                .map(name -> name + "=" + schema.getProperty(name))
                .toList();

        // then
        assertThat(schema.stringPropertyNames()).as("схема должна содержать свойства").isNotEmpty();
        assertThat(notSchema).as("значения вне вида ${VAR} (дефолт или литерал в коде)").isEmpty();
    }

    @Test
    @DisplayName("Переменные схемы без дефолтов и ключи infrastructure/.env.dev.example совпадают")
    void schemaVariables_declaredInEnvExample_noUnresolvedVariables() throws Exception {

        // given
        // when
        Properties schema = loadSchema();

        Set<String> allVariables = schema.stringPropertyNames()
                .stream()
                .map(schema::getProperty)
                .flatMap(value -> variables(value).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Переменные без дефолта обязательны к объявлению: у них нет значения в коде, и незаданная
        // подстановка валит старт. Переменные с ${VAR:default} объявлять не нужно — значение
        // возьмётся из схемы, *.env.example остаётся коротким.
        Set<String> requiredVariables = schema.stringPropertyNames()
                .stream()
                .map(schema::getProperty)
                .filter(value -> !SCHEMA_VALUE_WITH_DEFAULT.matcher(value).matches())
                .flatMap(value -> variables(value).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> declaredValues = appKeysOfEnvExample(ENV_DEV_EXAMPLE, allExampleSecrets());

        // then
        assertThat(declaredValues)
                .as("объявленный ключ не читается схемой application*.properties — лишний в окружении")
                .isSubsetOf(allVariables);
        // Проверка обязательных переменных берёт ВСЕ ключи шаблона (включая секреты): обязательность
        // секрета в контракте именно в том, что он без дефолта и объявлен в .env.
        // RUNTIME_SET_VARIABLES вычитаются — их объявляет не шаблон, а механизм запуска.
        Set<String> expectedDeclared = new LinkedHashSet<>(requiredVariables);
        expectedDeclared.removeAll(RUNTIME_SET_VARIABLES);
        assertThat(allEnvKeys(ENV_DEV_EXAMPLE))
                .as("обязательные переменные (без дефолта) объявлены в infrastructure/.env.dev.example")
                .containsAll(expectedDeclared);
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

        assertThat(appKeysOfEnvExample(ENV_DEV_EXAMPLE, allExampleSecrets()))
                .as("переменной ddl-auto в infrastructure/.env.dev.example быть не должно")
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

    /** Все секреты, которые должны быть в .env.dev.example (общие + dev-специфичные Keycloak). */
    private static Set<String> allExampleSecrets() {
        Set<String> s = new LinkedHashSet<>(ENV_EXAMPLE_SECRET_KEYS);
        s.addAll(DEV_ONLY_SECRET_KEYS);
        return s;
    }

    /**
     * Ключи приложения в {@code infrastructure/.env.dev.example}: только имя до первого {@code =}, комментарии и пустые
     * строки пропускаются. Значения здесь не проверяются — их смысл зависит от ключа, а не от формы. JVM-флаги,
     * инфраструктурные ключи compose и секреты исключаются.
     */
    private Set<String> appKeysOfEnvExample(Path envFile, Set<String> expectedSecrets) throws Exception {
        assertThat(Files.exists(envFile)).as(envFile.toString()).isTrue();
        Set<String> keys = new LinkedHashSet<>(allEnvKeys(envFile));
        assertThat(keys).as(envFile.toString()).isNotEmpty();
        assertThat(keys).as(envFile.toString() + ": содержит все секреты").containsAll(expectedSecrets);
        return keys.stream()
                .filter(key -> NON_SCHEMA_ENV_PREFIXES.stream().noneMatch(key::startsWith))
                .filter(key -> !NON_SCHEMA_ENV_KEYS.contains(key))
                .filter(key -> !ENV_EXAMPLE_SECRET_KEYS.contains(key))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Все ключи {@code infrastructure/.env.example}: имя до первого {@code =}, без комментариев и пустых строк. */
    private Set<String> allEnvKeys(Path envFile) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            assertThat(separator).as("строка без = : " + line).isPositive();
            keys.add(trimmed.substring(0, separator).trim());
        }
        return keys;
    }
}

package com.ukhanov.realhelpdesk.core.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт версии приложения: {@code pom.xml} — источник истины, остальные места обязаны ей соответствовать.
 *
 * <p>
 * Версия продублирована в четырёх местах (OpenAPI-спека, дефолты {@code OTEL_SERVICE_VERSION} в схеме properties,
 * env-карта run-конфигурации IDEA) и ни одно из них не выводится из pom автоматически: springdoc берёт строку из
 * {@link OpenApiConfiguration}, OTLP/ECS-поля — литерал после двоеточия в {@code ${VAR:default}}. При релизе (очередной
 * мажор или минор) пропуск любого места рассинхронизирует {@code service.version} в трейсах, логах и
 * {@code /v3/api-docs} с версией артефакта — тест вскрывает это на {@code mvn test}.
 * </p>
 *
 * <p>
 * Файлы читаются по путям репозитория (как в {@link ApplicationConfigSchemaTest}), а не с classpath: в jar-артефакте
 * run-конфигурации и pom нет.
 * </p>
 */
@DisplayName("Версия приложения: pom.xml — источник истины, все дубли согласованы")
class ProjectVersionConsistencyTest {

    private static final Path POM = Path.of("pom.xml");

    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    private static final Path IDEA_RUN_CONFIG = Path.of(".run", "RealHelpDeskApplication.run.xml");

    /**
     * Проектная версия: {@code <version>} сразу за собственным {@code <artifactId>} — parent и зависимости
     * отбрасываются позицией артефакта, а не порядком в файле.
     */
    private static final Pattern POM_VERSION = Pattern.compile(
            "<artifactId>realhelpdesk</artifactId>\\s*<version>([^<]+)</version>");

    /** Дефолт переменной версии в схеме: {@code ${OTEL_SERVICE_VERSION:x.y.z}}. */
    private static final Pattern OTEL_DEFAULT = Pattern.compile("\\$\\{OTEL_SERVICE_VERSION:([^}]+)}");

    /** Значение версии в env-карте IDEA-конфигурации. */
    private static final Pattern IDEA_ENV_VERSION = Pattern.compile(
            "key=\"OTEL_SERVICE_VERSION\"\\s+value=\"([^\"]+)\"");

    /** MAJOR.MINOR.PATCH — без SNAPSHOT и суффиксов: релизный тег v* берётся из этого же числа. */
    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    @Test
    @DisplayName("Версия pom.xml — корректный semver")
    void pomVersion_readFromBuild_returnsSemver() throws Exception {
        // given
        String version = pomVersion();

        // when
        // then
        assertThat(version).as("версия артефакта — MAJOR.MINOR.PATCH без суффиксов").matches(SEMVER);
    }

    @Test
    @DisplayName("OpenAPI, дефолты OTEL_SERVICE_VERSION и run-конфиг IDEA — версия как в pom.xml")
    void versionDuplicates_comparedToPom_areConsistent() throws Exception {
        // given
        String expected = pomVersion();

        // when
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("OpenApiConfiguration.version()", openApiVersion());
        // properties: OTLP service.version и ECS service.version — оба ${OTEL_SERVICE_VERSION:…}
        int index = 0;
        for (String value : allMatches(read(APPLICATION_PROPERTIES), OTEL_DEFAULT)) {
            actual.put("application.properties: OTEL_SERVICE_VERSION[" + (++index) + "]", value);
        }
        actual.put(".run/RealHelpDeskApplication.run.xml", firstMatch(read(IDEA_RUN_CONFIG), IDEA_ENV_VERSION));

        // then
        assertThat(actual).allSatisfy((source, value) -> assertThat(value)
                .as(source + " — рассинхрон с pom.xml (релиз: менять все места вместе)")
                .isEqualTo(expected));
    }

    private String pomVersion() throws Exception {
        return firstMatch(read(POM), POM_VERSION);
    }

    private String openApiVersion() {
        return new OpenApiConfiguration().realHelpDeskOpenAPI().getInfo().getVersion();
    }

    private static String read(Path file) throws Exception {
        assertThat(Files.exists(file)).as(file.toString()).isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<String> allMatches(String content, Pattern pattern) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String firstMatch(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find()).as("нет совпадения с " + pattern).isTrue();
        return matcher.group(1);
    }
}

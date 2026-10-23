package com.ukhanov.realhelpdesk;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ukhanov.realhelpdesk.core.config.OpenApiConfiguration;
import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.security.keycloak.config.KeycloakWhiteUrlConfig;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.WhiteUrlConfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Контракт OpenAPI-документации: спека безопасна и полна.
 *
 * <p>
 * 1) В prod-контуре springdoc выключен — контракт не раскрывается без аутентификации (в white-list security
 * документация permitAll во всех профилях). 2) SecurityScheme (Bearer JWT) описан — в swagger-ui есть кнопка Authorize.
 * 3) Глобальный обработчик исключений размечен {@link ApiResponse} — в описании каждой операции видны коды ошибок и
 * структура problem+json. 4) Контроллеры документируют реальные статусы: код в спеке совпадает с кодом, возвращаемым
 * методом (201/202/204/206/416), а не дефолтный 200. 5) Контракт схем ({@code openapi/schema-contract.json}) полон: у
 * каждой схемы пример и описания полей — фронтенд не спрашивает бэкендера форматы.
 * </p>
 */
@DisplayName("OpenAPI: спека в prod отключена, авторизация и ошибки задокументированы")
class OpenApiDocumentationGuardTest {

    private static final Path PROD_PROPERTIES = Path.of("src", "main", "resources", "application-prod.properties");

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    private static final Path SCHEMA_CONTRACT = Path.of("src", "main", "resources", "openapi", "schema-contract.json");

    /**
     * Схемы фреймворка, не несущие смысла для клиента: в контракте быть не должно (ловим утечки из DTO/контроллеров).
     */
    private static final Set<String> FORBIDDEN_CONTRACT_SCHEMAS = Set.of("StreamingResponseBody", "SseEmitter");

    /** Обработчик ResponseStatusException произвольно транслирует статусы фреймворка — его коды не фиксируются. */
    private static final Set<String> DYNAMIC_HANDLERS = Set.of("handleResponseStatusException");

    private static final Set<String> REQUIRED_ERROR_CODES = Set.of("400", "401", "403", "404", "500");

    /**
     * Реальные не-200 статусы, возвращаемые контроллерами напрямую: строка вызова в теле метода ↔ код, который обязан
     * быть задокументирован {@code @ApiResponse}. 200 не входит — он дефолтный и не требует аннотации.
     */
    private static final Map<String, String> STATUS_CALLS = Map.of("ResponseEntity.created(", "201",
            "ResponseEntity.accepted(", "202",
            "ResponseEntity.noContent()", "204", "HttpStatus.PARTIAL_CONTENT", "206",
            "HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE", "416");

    @Test
    @DisplayName("Профиль prod отключает спеку и UI — контракт API не утекает из рабочего контура")
    void prodProfile_springdocEnabled_equalsFalse() throws Exception {

        // given
        // when
        Properties prod = new Properties();
        try (InputStream in = Files.newInputStream(PROD_PROPERTIES)) {
            prod.load(in);
        }

        // then
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).as("springdoc.api-docs.enabled").isEqualTo("false");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).as("springdoc.swagger-ui.enabled")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("Документация в white-list обеих security-цепочек — swagger доступен в dev без аутентификации")
    void swaggerPaths_bothSecurityContours_arePermitted() {

        // given / when / then
        assertThat(WhiteUrlConfig.WHITE_LIST_URLS).as("контур без keycloak")
                .contains("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                        "/swagger-ui/**");
        assertThat(KeycloakWhiteUrlConfig.WHITE_LIST_URLS).as("контур keycloak")
                .contains("/v3/api-docs", "/v3/api-docs/**",
                        "/swagger-ui.html", "/swagger-ui/**");
    }

    @Test
    @DisplayName("SecurityScheme Bearer JWT описан — в swagger-ui есть кнопка Authorize")
    void securityScheme_bearerJwtHttp_isDeclared() {

        // given
        // when
        SecurityScheme scheme = OpenApiConfiguration.class.getAnnotation(SecurityScheme.class);
        // then
        assertThat(scheme).as("@SecurityScheme на конфигурации").isNotNull();
        assertThat(scheme.type()).isEqualTo(SecuritySchemeType.HTTP);
        assertThat(scheme.scheme()).isEqualTo("bearer");
        assertThat(scheme.bearerFormat()).isEqualTo("JWT");

        OpenAPI openApi = new OpenApiConfiguration().realHelpDeskOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("RealHelpDesk API");
        assertThat(openApi.getSecurity()).as("глобальный security-requirement").isNotEmpty();
    }

    @Test
    @DisplayName("Каждый @ExceptionHandler размечен @ApiResponse — коды ошибок видны в спеке каждой операции")
    void exceptionHandlers_missingApiResponse_returnsEmpty() {

        // given
        // when
        Set<String> documentedCodes = new TreeSet<>();
        Set<String> unannotated = new TreeSet<>();

        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (method.getAnnotation(ExceptionHandler.class) == null) {
                continue;
            }
            ApiResponse[] responses = method.getAnnotationsByType(ApiResponse.class);
            if (responses.length == 0) {
                if (!DYNAMIC_HANDLERS.contains(method.getName())) {
                    unannotated.add(method.getName());
                }
                continue;
            }
            for (ApiResponse response : responses) {
                documentedCodes.add(response.responseCode());
            }
        }

        // then
        assertThat(GlobalExceptionHandler.class.getAnnotation(RestControllerAdvice.class))
                .as("обработчик — @RestControllerAdvice (springdoc тиражирует ответы во все операции)")
                .isNotNull();
        assertThat(unannotated).as("обработчики без @ApiResponse (кроме динамических)").isEmpty();
        assertThat(documentedCodes).as("коды ошибок в описании операций").containsAll(REQUIRED_ERROR_CODES);
    }

    @Test
    @DisplayName("Контроллеры документируют реальные статусы: @ApiResponse совпадает с кодом, возвращаемым методом")
    void controllers_apiResponseStatusMismatch_returnsEmpty() throws IOException {

        // given
        // when
        List<Path> controllers = controllerFiles();
        // then
        assertThat(controllers).as("контроллеры найдены").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path controller : controllers) {
            for (String block : methodBlocks(read(controller))) {
                if (!block.contains("public ")) {
                    continue;
                }
                // Spotless переносит fluent-вызовы (ResponseEntity\n.created), поэтому
                // сравнение идёт по склеенному тексту без пробелов и переводов строк.
                String compact = block.replaceAll("\\s+", "");
                for (Map.Entry<String, String> call : STATUS_CALLS.entrySet()) {
                    String code = call.getValue();
                    boolean usesCall = compact.contains(call.getKey().replaceAll("\\s+", ""));
                    boolean documentsCode = compact.contains("@ApiResponse(responseCode=\"" + code + "\"");
                    if (usesCall && !documentsCode) {
                        violations.add("%s: %s без @ApiResponse(%s)".formatted(controller, call.getKey(), code));
                    }
                    if (documentsCode && !usesCall) {
                        violations.add("%s: @ApiResponse(%s) без %s".formatted(controller, code, call.getKey()));
                    }
                }
            }
        }

        assertThat(violations).as("расхождения кода возврата и кода в спеке").isEmpty();
    }

    @Test
    @DisplayName("Контракт схем полон: у каждой схемы пример и описания полей, схемы фреймворка не описаны")
    void schemaContract_missingExampleOrFields_returnsEmpty() throws IOException {

        // given
        // when
        JsonNode root = new ObjectMapper().readTree(SCHEMA_CONTRACT.toFile());
        // then
        assertThat(root.isObject()).as("контракт — JSON-объект").isTrue();

        List<String> violations = new ArrayList<>();
        int schemas = 0;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String name = entry.getKey();
            if (name.startsWith("_")) {
                continue;
            }
            schemas++;
            validateSchema(name, entry.getValue(), violations);
        }

        assertThat(schemas).as("схем в контракте").isGreaterThanOrEqualTo(50);
        assertThat(violations).as("неполнота контракта спеки").isEmpty();
    }

    private static void validateSchema(String name, JsonNode node, List<String> violations) {
        if (FORBIDDEN_CONTRACT_SCHEMAS.contains(name)) {
            violations.add("схема фреймворка %s в контракте".formatted(name));
            return;
        }
        if (isBlank(node, "description")) {
            violations.add("%s: нет description схемы".formatted(name));
        }
        if (missingExample(node)) {
            violations.add("%s: нет примера (example-объект)".formatted(name));
        }
        if (missingFields(node)) {
            violations.add("%s: нет описаний полей".formatted(name));
            return;
        }
        checkFieldDescriptions(name, node.get("fields"), violations);
    }

    private static boolean missingExample(JsonNode node) {
        JsonNode example = node.get("example");
        return example == null || !example.isObject() || example.isEmpty();
    }

    private static boolean missingFields(JsonNode node) {
        JsonNode fields = node.get("fields");
        return fields == null || !fields.isObject() || fields.isEmpty();
    }

    private static void checkFieldDescriptions(String name, JsonNode fields, List<String> violations) {
        for (Map.Entry<String, JsonNode> field : fields.properties()) {
            if (field.getValue() == null || field.getValue().stringValue().isBlank()) {
                violations.add("%s.%s: пустое описание".formatted(name, field.getKey()));
            }
        }
    }

    private static boolean isBlank(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.stringValue().isBlank();
    }

    private static List<Path> controllerFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            return paths.filter(path -> path.getFileName().toString().endsWith("Controller.java")).sorted().toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Члены класса — блок «аннотации + сигнатура + тело» до закрывающей скобки на отступе класса (4 пробела): вложенные
     * блоки закрываются с бо́льшим отступом и границу не создают.
     */
    private static List<String> methodBlocks(String source) {
        String[] lines = source.split("\r?\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            current.append(line).append('\n');
            if ("    }".equals(line)) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }
        return blocks;
    }
}

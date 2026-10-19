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

/**
 * Контракт OpenAPI-документации: спека безопасна и полна.
 *
 * <p>
 * 1) В prod-контуре springdoc выключен — контракт не раскрывается без аутентификации (в white-list security документация permitAll во всех
 * профилях). 2) SecurityScheme (Bearer JWT) описан — в swagger-ui есть кнопка Authorize. 3) Глобальный обработчик исключений размечен
 * {@link ApiResponse} — в описании каждой операции видны коды ошибок и структура problem+json. 4) Контроллеры документируют реальные
 * статусы: код в спеке совпадает с кодом, возвращаемым методом (201/202/204/206/416), а не дефолтный 200.
 * </p>
 */
@DisplayName("OpenAPI: спека в prod отключена, авторизация и ошибки задокументированы")
class OpenApiDocumentationGuardTest {

    private static final Path PROD_PROPERTIES = Path.of("src", "main", "resources", "application-prod.properties");

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** Обработчик ResponseStatusException произвольно транслирует статусы фреймворка — его коды не фиксируются. */
    private static final Set<String> DYNAMIC_HANDLERS = Set.of("handleResponseStatusException");

    private static final Set<String> REQUIRED_ERROR_CODES = Set.of("400", "401", "403", "404", "500");

    /**
     * Реальные не-200 статусы, возвращаемые контроллерами напрямую: строка вызова в теле метода ↔ код, который обязан быть задокументирован
     * {@code @ApiResponse}. 200 не входит — он дефолтный и не требует аннотации.
     */
    private static final Map<String, String> STATUS_CALLS = Map.of("ResponseEntity.created(", "201", "ResponseEntity.accepted(", "202",
            "ResponseEntity.noContent()", "204", "HttpStatus.PARTIAL_CONTENT", "206", "HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE", "416");

    @Test
    @DisplayName("Профиль prod отключает спеку и UI — контракт API не утекает из рабочего контура")
    void prodProfileDisablesSpringdoc() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = Files.newInputStream(PROD_PROPERTIES)) {
            prod.load(in);
        }
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).as("springdoc.api-docs.enabled").isEqualTo("false");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).as("springdoc.swagger-ui.enabled").isEqualTo("false");
    }

    @Test
    @DisplayName("Документация в white-list обеих security-цепочек — swagger доступен в dev без аутентификации")
    void swaggerPathsArePermittedInBothContours() {
        assertThat(WhiteUrlConfig.WHITE_LIST_URLS).as("контур без keycloak").contains("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                "/swagger-ui/**");
        assertThat(KeycloakWhiteUrlConfig.WHITE_LIST_URLS).as("контур keycloak").contains("/v3/api-docs", "/v3/api-docs/**",
                "/swagger-ui.html", "/swagger-ui/**");
    }

    @Test
    @DisplayName("SecurityScheme Bearer JWT описан — в swagger-ui есть кнопка Authorize")
    void securitySchemeIsDeclared() {
        SecurityScheme scheme = OpenApiConfiguration.class.getAnnotation(SecurityScheme.class);
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
    void globalHandlerDocumentsErrorCodes() {
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

        assertThat(GlobalExceptionHandler.class.getAnnotation(RestControllerAdvice.class))
                .as("обработчик — @RestControllerAdvice (springdoc тиражирует ответы во все операции)").isNotNull();
        assertThat(unannotated).as("обработчики без @ApiResponse (кроме динамических)").isEmpty();
        assertThat(documentedCodes).as("коды ошибок в описании операций").containsAll(REQUIRED_ERROR_CODES);
    }

    @Test
    @DisplayName("Контроллеры документируют реальные статусы: @ApiResponse совпадает с кодом, возвращаемым методом")
    void controllersDocumentRealStatusCodes() throws IOException {
        List<Path> controllers = controllerFiles();
        assertThat(controllers).as("контроллеры найдены").isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (Path controller : controllers) {
            for (String block : methodBlocks(read(controller))) {
                if (!block.contains("public ")) {
                    continue;
                }
                for (Map.Entry<String, String> call : STATUS_CALLS.entrySet()) {
                    String code = call.getValue();
                    boolean usesCall = block.contains(call.getKey());
                    boolean documentsCode = block.contains("@ApiResponse(responseCode = \"" + code + "\"");
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

    private static List<Path> controllerFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            return paths.filter(path -> path.getFileName().toString().endsWith("Controller.java")).sorted().toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Члены класса — блок «аннотации + сигнатура + тело» до закрывающей скобки на отступе класса (4 пробела): вложенные блоки закрываются с
     * бо́льшим отступом и границу не создают.
     */
    private static List<String> methodBlocks(String source) {
        String[] lines = source.split("\r?\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            current.append(line).append('\n');
            if (line.equals("    }")) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }
        return blocks;
    }
}

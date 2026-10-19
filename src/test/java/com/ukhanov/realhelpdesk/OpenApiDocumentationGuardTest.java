package com.ukhanov.realhelpdesk;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

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
 * {@link ApiResponse} — в описании каждой операции видны коды ошибок и структура problem+json.
 * </p>
 */
@DisplayName("OpenAPI: спека в prod отключена, авторизация и ошибки задокументированы")
class OpenApiDocumentationGuardTest {

    private static final Path PROD_PROPERTIES = Path.of("src", "main", "resources", "application-prod.properties");

    /** Обработчик ResponseStatusException произвольно транслирует статусы фреймворка — его коды не фиксируются. */
    private static final Set<String> DYNAMIC_HANDLERS = Set.of("handleResponseStatusException");

    private static final Set<String> REQUIRED_ERROR_CODES = Set.of("400", "401", "403", "404", "500");

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
}

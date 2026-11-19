package com.ukhanov.realhelpdesk.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * Конфигурация OpenAPI-спеки (springdoc собирает её из контроллеров и DTO).
 *
 * <p>
 * {@link SecurityScheme} описывает авторизацию Bearer JWT (токен выпускает Keycloak): в swagger-ui появляется кнопка
 * Authorize, токен подставляется во все запросы. Глобальный security-requirement помечает все операции как требующие
 * аутентификации.
 * </p>
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfiguration {

    @Bean
    public OpenAPI realHelpDeskOpenAPI() {
        return new OpenAPI().info(new Info().title("RealHelpDesk API")
                // Дублирует pom.xml и дефолт OTEL_SERVICE_VERSION; согласованность проверяет
                // ProjectVersionConsistencyTest — при релизе менять все места вместе
                .version("2.0.0")
                .description(
                        """
                                REST API системы управления заявками, базовый URL — /api/v1.

                                Ответы ошибок — application/problem+json:
                                { type, title, status, detail, instance, timestamp };
                                ошибки валидации добавляют errors, провал капчи — code,
                                лимит запроса — Retry-After (429).
                                Списки: ?page=(с нуля)&size≤100&sortBy&order; пагинированный ответ — PageResponse.

                                Аутентификация: Authorization: Bearer <access_token> — токен
                                выпускает Keycloak, подпись проверяется по JWK.

                                Полные правила и коды ответов — docs/api.md."""))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}

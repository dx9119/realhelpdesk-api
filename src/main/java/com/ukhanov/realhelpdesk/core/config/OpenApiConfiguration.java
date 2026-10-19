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
 * {@link SecurityScheme} описывает авторизацию Bearer JWT (контур keycloak — прод и dev по умолчанию): в swagger-ui появляется кнопка
 * Authorize, токен подставляется во все запросы. Глобальный security-requirement помечает все операции как требующие аутентификации. Контур
 * без keycloak принимает токен в HttpOnly-cookie ({@code accessToken}) — кнопка Authorize на него не действует, это описано в
 * {@code docs/api.md} и в описании спеки.
 * </p>
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfiguration {

    @Bean
    public OpenAPI realHelpDeskOpenAPI() {
        return new OpenAPI().info(new Info().title("RealHelpDesk API")
                // 0.0.1-SNAPSHOT — как в pom.xml и в дефолте OTEL_SERVICE_VERSION (.env.example)
                .version("0.0.1-SNAPSHOT").description("""
                        REST API системы управления заявками, базовый URL — /api/v1.

                        Ответы ошибок — application/problem+json: { type, title, status, detail, instance, timestamp };
                        ошибки валидации добавляют errors, провал капчи — code, лимит запроса — Retry-After (429).
                        Списки: ?page=(с нуля)&size≤100&sortBy&order; пагинированный ответ — PageResponse.
                        Изменяющие запросы (POST/PUT/PATCH/DELETE) в контуре без keycloak обязаны нести X-Requested-With.

                        Аутентификация: контур keycloak — Authorization: Bearer <access_token>;
                        контур без keycloak — токен в HttpOnly-cookie accessToken (кнопка Authorize неприменима).

                        Полные правила и коды ответов — docs/api.md.""")).addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}

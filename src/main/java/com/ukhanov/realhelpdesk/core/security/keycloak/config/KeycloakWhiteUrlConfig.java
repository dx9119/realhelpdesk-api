package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

/**
 * Белые URL приложения: публичные пути security-цепочки.
 *
 * <p>
 * В списке нет ни одного эндпоинта аутентификации: вход, регистрация и восстановление пароля живут в Keycloak,
 * приложение их не обслуживает.
 * </p>
 */
@Configuration
public class KeycloakWhiteUrlConfig {

    /**
     * Публичные пути: капча (её решают анонимные формы), health и контракт OpenAPI. Выдача кода на почту
     * ({@code POST /api/v1/email/codes}) сюда не входит — она требует bearer-токена.
     */
    public static final List<String> WHITE_LIST_URLS = List.of("/api/v1/captcha/challenge",
            "/api/v1/captcha/solve",
            "/captcha/widget.js", "/api/v1/health",
            // Actuator (health/info/prometheus) живёт на отдельном управляющем порту
            // (MANAGEMENT_PORT) и обслуживается ManagementSecurityConfiguration, а не этой
            // цепочкой: правила на /actuator/** здесь не нужны, иначе белый список отдал бы
            // метрики без аутентификации на основном порту.
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            // Dev-only сброс рейт-лимитов для e2e (DevOpsController, @Profile("dev"): вне dev эндпоинта нет)
            "/api/v1/dev/ratelimit-reset");

}

package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Белые URL профиля {@code keycloak}: то, что остаётся публичным, когда токены выпускает Keycloak.
 *
 * <p>
 * В отличие от {@code WhiteUrlConfig} локального контура здесь нет ни одного эндпоинта аутентификации: вход и
 * регистрация живут в Keycloak, а cookie-эндпоинты приложения в этом профиле не создаются (AuthController помечен
 * {@code @Profile("!keycloak")}). Восстановление пароля тоже уходит в Keycloak — приложение его не обслуживает, потому
 * что {@code password_hash} в keycloak-контуре не используется для входа.
 * </p>
 */
@Configuration
@Profile("keycloak")
public class KeycloakWhiteUrlConfig {

    /**
     * Публичные пути: капча (её решают анонимные формы), health и контракт OpenAPI. Капча остаётся общей для обоих
     * контуров — в keycloak-профиле публичными остаются те же write-эндпоинты приложения (например, выдача кода на
     * почту), и защищает их тот же captcha-токен.
     */
    public static final List<String> WHITE_LIST_URLS = List.of("/api/v1/captcha/challenge",
            "/api/v1/captcha/solve",
            "/captcha/widget.js", "/api/v1/health",
            // Actuator (health/info/prometheus) живёт на отдельном управляющем порту
            // (MANAGEMENT_PORT) и обслуживается ManagementSecurityConfiguration, а не этой
            // цепочкой: правила на /actuator/** здесь не нужны, иначе белый список отдал бы
            // метрики без аутентификации на основном порту.
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            // Dev-only сброс рейт-лимитов для e2e (DevOpsController, @Profile("dev"): в prod эндпоинта нет)
            "/api/v1/dev/ratelimit-reset");

}

package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

@Configuration
public class WhiteUrlConfig {
    public static final List<String> WHITE_LIST_URLS = List.of("/api/v1/auth/login", "/api/v1/auth/register",
            "/api/v1/auth/tokens/access", "/api/v1/captcha/challenge", "/api/v1/captcha/solve", "/captcha/widget.js",
            "/api/v1/health",
            "/api/v1/users/password-resets", "/api/v1/users/password-resets/**",
            // Actuator (health/info/prometheus) живёт на отдельном управляющем порту
            // (MANAGEMENT_PORT) и обслуживается ManagementSecurityConfiguration, а не этой
            // цепочкой: правила на /actuator/** здесь не нужны, иначе белый список отдал бы
            // метрики без аутентификации на основном порту.
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            // Dev-only сброс рейт-лимитов для e2e (DevOpsController, @Profile("dev"): в prod эндпоинта нет)
            "/api/v1/dev/ratelimit-reset");

}

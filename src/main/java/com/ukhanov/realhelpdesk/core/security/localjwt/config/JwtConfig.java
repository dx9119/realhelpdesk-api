package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import java.security.Key;
import java.util.Base64;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.security.Keys;
import lombok.Getter;

/**
 * Готовый к работе конфиг JWT: проверяет настройки из {@link JwtProperties} и собирает ключ подписи. Падает на старте с
 * понятной ошибкой, если секрет или адреса не заданы.
 *
 * <p>
 * Профиль {@code keycloak} его не отключает: выдачу и отзыв токенов локального контура (register/login/refresh)
 * собирают {@code GenTokenService}, {@code GetTokenService} и {@code RefreshTokenCleanupService} — все они зависят от
 * этого бина. В keycloak-контуре входящие токены проверяет {@code KeycloakSecurityConfiguration}, поэтому
 * {@code JwtConfig} остаётся неработающим «нагрузочным» конфигом, а не точкой отказа.
 * </p>
 */
@Component
@Getter
public class JwtConfig {

    private final Key jwtKey;
    private final String issuer;
    private final Set<String> audience;
    private final Integer accessTokenExpirationSeconds;
    private final Integer refreshTokenExpirationSeconds;

    public JwtConfig(JwtProperties properties) {
        String secretForGenJwt = requireNonBlank(properties.getSecretForGenJwt(), "jwt.secret-for-gen-jwt");
        String issuerFromProperties = requireNonBlank(properties.getIssuer(), "jwt.issuer");
        Set<String> audienceFromProperties = requireNonEmpty(properties.getAudience(), "jwt.audience");
        Integer accessExpSeconds = requirePositive(properties.getAccessTokenExpirationSeconds(),
                "jwt.access-token-expiration-seconds");
        Integer refreshExpSeconds = requirePositive(properties.getRefreshTokenExpirationSeconds(),
                "jwt.refresh-token-expiration-seconds");

        try {
            this.jwtKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secretForGenJwt));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Ошибка при декодировании секрета JWT: должен быть корректной строкой в формате Base64 "
                            + "достаточной длины для генерации ключа HMAC (HS256 требует ключ длиной 256 бит).",
                    e);
        }

        this.issuer = issuerFromProperties;
        this.audience = audienceFromProperties;
        this.accessTokenExpirationSeconds = accessExpSeconds;
        this.refreshTokenExpirationSeconds = refreshExpSeconds;
    }

    private static String requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " не содержит значение");
        }
        return value;
    }

    private static Set<String> requireNonEmpty(Set<String> value, String property) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(property + " не содержит значение");
        }
        return value;
    }

    private static Integer requirePositive(Integer value, String property) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(property + " не содержит значение");
        }
        return value;
    }
}

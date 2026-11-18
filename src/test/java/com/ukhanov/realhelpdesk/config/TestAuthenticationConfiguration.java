package com.ukhanov.realhelpdesk.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Тестовый контур аутентификации для интеграционных тестов.
 *
 * <p>
 * Приложение в единственном контуре проверяет bearer-токены Keycloak'ом по JWK ({@code jwk-set-uri}). Поднимать
 * Keycloak в Testcontainers ради каждого сквозного теста дорого, а ходить в сеть из тестов нельзя, поэтому здесь
 * подменяется {@link JwtDecoder}: приложение остаётся за {@code SecurityFilterChain} (цепочка, entry point, роль-фильтр
 * и {@code KeycloakAuthenticationConverter} — боевые), но подпись не проверяется, а claim'ы берутся из самого токена.
 * Автоконфигурация Boot свой decoder не создаёт, потому что бин уже объявлен.
 * </p>
 *
 * <p>
 * Формат тестового токена — {@code test-token.<hex email>}: адрес кодируется hex'ом, потому что
 * {@code DefaultBearerTokenResolver} принимает только RFC-6750 b64token ({@code [A-Za-z0-9-._~+/]+=*}), и символ
 * {@code @} в заголовке {@code Authorization} даёт «Bearer token is malformed» — 401 ещё до вызова декодера. Любой
 * другой токен отклоняется, и запрос получает 401 от боевого entry point — так проверки «без токена доступ закрыт»
 * остаются настоящими.
 * </p>
 *
 * <p>
 * Учётная запись заводится боевым {@code KeycloakAuthenticationConverter} при первом запросе
 * ({@code keycloak.auto-create-users=true} в тестовом профиле), поэтому principal — числовой id из БД, как в проде, и
 * {@code CurrentUserProvider} работает без специальных тестовых фикстур.
 * </p>
 */
@Configuration
@Profile("test")
public class TestAuthenticationConfiguration {

    /** Префикс тестового токена: всё, что не начинается с него, считается невалидным. */
    public static final String TOKEN_PREFIX = "test-token.";

    /** Email штатного тестового пользователя: учётная запись создаётся конвертером на первом запросе. */
    public static final String TEST_EMAIL = "test-auth@example.com";

    /** Claim с адресом пользователя — то же имя, что читает KeycloakAuthenticationConverter. */
    private static final String EMAIL_CLAIM = "email";

    /** Токен для адреса: hex держит значение в алфавите, допустимом для bearer-резолвера. */
    public static String tokenFor(String email) {
        return TOKEN_PREFIX + HexFormat.of().formatHex(email.getBytes(StandardCharsets.UTF_8));
    }

    /** Токен штатного тестового пользователя. */
    public static String testToken() {
        return tokenFor(TEST_EMAIL);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            if (token == null || !token.startsWith(TOKEN_PREFIX)) {
                throw new JwtException("Не тестовый токен");
            }
            String email = decodeEmail(token.substring(TOKEN_PREFIX.length()));
            if (email.isBlank()) {
                throw new JwtException("Пустой email в тестовом токене");
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put(OAuth2TokenIntrospectionClaimNames.SUB, "kc-" + email);
            claims.put(EMAIL_CLAIM, email);

            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claims(map -> map.putAll(claims))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .build();
        };
    }

    /** Обратное к {@link #tokenFor(String)}: битый hex — не тестовый токен. */
    private static String decodeEmail(String hex) {
        try {
            return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Не тестовый токен", e);
        }
    }
}

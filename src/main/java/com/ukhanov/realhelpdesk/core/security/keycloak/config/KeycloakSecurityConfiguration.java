package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import com.ukhanov.realhelpdesk.core.security.handler.ProblemJsonAccessDeniedHandler;
import com.ukhanov.realhelpdesk.core.security.handler.ProblemJsonAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

/**
 * Безопасность приложения: входящие токены проверяет OAuth2 Resource Server, выпускает их Keycloak.
 *
 * <p>
 * Источник истины — Keycloak: подпись, срок, issuer и audience проверяет Spring Security по JWK
 * ({@code jwk-set-uri}/{@code issuer-uri} из {@code application.properties}), а пользователь и роль приходят из
 * {@link KeycloakAuthenticationConverter}. Приложение токен не выпускает и локальный пароль не хранит.
 * </p>
 *
 * <p>
 * Отдельный {@link JwtAuthenticationProvider} собирается здесь явно: провайдер принимает только bearer-токены, поэтому
 * форма входа и Basic-аутентификация на основном порту не работают. CSRF не включается — токен клиент передаёт
 * заголовком, который браузер не подставляет сам.
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class KeycloakSecurityConfiguration {

    private final KeycloakAuthenticationConverter keycloakAuthenticationConverter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ProblemJsonAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemJsonAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain keycloakFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        // Только JWT-провайдер: пароль приложение не проверяет, а явный провайдер исключает
        // случайное совпадение с DaoAuthenticationProvider управляющего порта.
        JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
        jwtAuthenticationProvider.setJwtAuthenticationConverter(keycloakAuthenticationConverter);

        http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize
                        // OPTIONS — preflight без аутентификации
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Публичные URL (капча, health, swagger)
                        .requestMatchers(KeycloakWhiteUrlConfig.WHITE_LIST_URLS.toArray(String[]::new))
                        .permitAll()
                        // Всё остальное — с аутентификацией
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .authenticationManager(new ProviderManager(List.of(jwtAuthenticationProvider)))
                                .jwtAuthenticationConverter(keycloakAuthenticationConverter))
                        // Единый problem+json вместо пустого тела по умолчанию: 401 — токена нет/невалиден,
                        // 403 — аутентификация есть, прав нет.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(
                        exceptionHandling -> exceptionHandling.authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler))
                // CSRF-защита не нужна: токен приходит заголовком Authorization, который
                // браузер сам не подставляет.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}

package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
 * Безопасность профиля {@code keycloak}: входящие токены проверяет OAuth2 Resource Server, выпускает их Keycloak.
 *
 * <p>
 * Отличие от локального контура ({@code WebSecurityConfiguration}) — в источнике истины: там подпись и срок проверяет
 * фильтр приложения, а пользователь и версия токена читаются из БД; здесь подпись, срок, issuer и audience проверяет
 * Spring Security по JWK Keycloak ({@code jwk-set-uri}/{@code issuer-uri} из {@code application-keycloak.properties}),
 * а пользователь и роль приходят из {@link KeycloakAuthenticationConverter}.
 * </p>
 *
 * <p>
 * Отдельный {@link JwtAuthenticationProvider} собирается здесь явно и не смешивается с
 * {@code DaoAuthenticationProvider} приложения (логин по паролю в этом профиле не используется): провайдер принимает
 * только bearer-токены, поэтому форма входа и Basic-аутентификация на основном порту не работают. CSRF-фильтр
 * приложения тоже не создаётся — в этом профиле нет cookie-аутентификации, которую он защищает.
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("keycloak")
@RequiredArgsConstructor
public class KeycloakSecurityConfiguration {

    private final KeycloakAuthenticationConverter keycloakAuthenticationConverter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ProblemJsonAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemJsonAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain keycloakFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        // Только JWT-провайдер: пароль в keycloak-профиле не проверяется, а явный провайдер исключает
        // случайное совпадение с DaoAuthenticationProvider приложения.
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
                // Cookie-аутентификации в этом профиле нет: CSRF-защита не нужна (и не создаётся —
                // CsrfProtectionFilter помечен @Profile("!keycloak")).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}

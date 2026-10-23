package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.ukhanov.realhelpdesk.core.filter.LoggingFilter;
import com.ukhanov.realhelpdesk.core.security.handler.ProblemJsonAccessDeniedHandler;
import com.ukhanov.realhelpdesk.core.security.handler.ProblemJsonAuthenticationEntryPoint;
import com.ukhanov.realhelpdesk.core.security.localjwt.filter.CsrfProtectionFilter;
import com.ukhanov.realhelpdesk.core.security.localjwt.filter.JwtAuthFilter;

import lombok.RequiredArgsConstructor;

// For List.of()

/**
 * Конфигурация безопасности для профиля, отличного от {@code keycloak}. Использует локальную JWT-подпись (JwtConfig) и
 * cookie-аутентификацию. В профиле {@code keycloak} используется {@link KeycloakSecurityConfiguration}.
 */
@RequiredArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!keycloak")
public class WebSecurityConfiguration {

    private final JwtAuthFilter jwtAuthFilter;
    private final LoggingFilter loggingFilter;
    private final CsrfProtectionFilter csrfProtectionFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ProblemJsonAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemJsonAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Фильтры
        // Важно: порядок вызовов задаёт и порядок в цепочке. Сначала регистрируем JwtAuthFilter —
        // только после этого Spring знает его позицию, — затем вставляем фильтры «перед ним»:
        // каждый следующий вызов встаёт непосредственно перед JwtAuthFilter.
        // loggingFilter регистрируем первым из трёх: он должен оборачивать CSRF-проверку, иначе
        // отказ CSRF (403) уходит в лог без MDC и не попадает в access-лог.
        http.addFilterBefore(jwtAuthFilter, BasicAuthenticationFilter.class);
        http.addFilterBefore(loggingFilter, JwtAuthFilter.class);
        http.addFilterBefore(csrfProtectionFilter, JwtAuthFilter.class);
        http.cors(cors -> cors.configurationSource(corsConfigurationSource));
        // Доступ
        http.authorizeHttpRequests(authorize -> authorize
                // 1. Разрешаем все OPTIONS запросы БЕЗ АУТЕНТИКАЦИИ для Preflight запросов
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()

                // 2. Разрешаем доступ к публичным URL
                // Эти пути не будут требовать JWT токена
                .requestMatchers(WhiteUrlConfig.WHITE_LIST_URLS.toArray(String[]::new))
                .permitAll()

                // 3. Все остальные запросы требуют аутентификации.
                .anyRequest()
                .authenticated());

        http.headers(Customizer.withDefaults());
        http.anonymous(AbstractHttpConfigurer::disable);
        // Единый problem+json вместо 403 с пустым телом по умолчанию: 401 — запрос без
        // аутентификации, 403 — аутентификация есть, прав нет.
        http.exceptionHandling(exceptionHandling -> exceptionHandling.authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));
        // CSRF держится на обязательном заголовке (CsrfProtectionFilter), а не на
        // встроенной защите Spring: см. javadoc фильтра.
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(
                sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

}

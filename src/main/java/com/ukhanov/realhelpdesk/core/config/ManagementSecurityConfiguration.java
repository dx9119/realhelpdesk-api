package com.ukhanov.realhelpdesk.core.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Безопасность управляющего порта: actuator живёт на отдельном порту ({@code MANAGEMENT_PORT}), поэтому на основном
 * сервере {@code /actuator/**} не обслуживается, а здесь — свой набор правил, независимый от JWT.
 *
 * <p>
 * Цепочка {@code @Order(0)} (выше основной) перехватывает все пути actuator на обоих портах: health и info отдаются без
 * аутентификации (пробы оркестратора), prometheus — по HTTP Basic сервисной учёткой из
 * {@link PrometheusScrapeProperties}, всё остальное запрещено. Основная цепочка {@code WebSecurityConfiguration} правил
 * на {@code /actuator/**} больше не содержит и сюда не попадает: JwtAuthFilter, CsrfProtectionFilter и CORS
 * настраиваются только в ней, поэтому скрейп не ходит через аутентификацию приложения.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Configuration
public class ManagementSecurityConfiguration {

    /** Роль сервисной учётки скрейпа: читает только /actuator/prometheus. */
    private static final String METRICS_ROLE = "METRICS";

    private final PrometheusScrapeProperties scrapeProperties;

    @Bean
    @Order(0)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        boolean authEnabled = scrapeProperties.isEnabled();
        if (authEnabled && !scrapeProperties.hasCredentials()) {
            logger.warn("Учётные данные скрейпа метрик не заданы (METRICS_USER/METRICS_PASSWORD): "
                    + "/actuator/prometheus остаётся закрытым для всех запросов");
        }

        // Матчер обязателен: без него у цепочки AnyRequestMatcher и Spring Security 7 падает
        // UnreachableFilterChainException («только одна цепочка на any request, и она должна быть последней»).
        // EndpointRequest проверяет контекст запроса: на основном порту он игнорируется (namespace management
        // отсутствует), а /actuator/** страхует мусорные пути управляющего порта, чтобы они не уходили
        // в основную цепочку с JWT-фильтром.
        RequestMatcher actuatorMatcher = new OrRequestMatcher(EndpointRequest.toAnyEndpoint(),
                PathPatternRequestMatcher.withDefaults().matcher("/actuator/**"));
        http.securityMatcher(actuatorMatcher);

        // Без сессий и CSRF: скрейп — одиночный GET с Basic-заголовком, куки и токены не используются.
        http.sessionManagement(
                sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.csrf(AbstractHttpConfigurer::disable);
        // Basic ставим всегда, даже при выключенной проверке: он задаёт entry point,
        // поэтому неаутентифицированный запрос получает 401, а не 403 без объяснений.
        http.httpBasic(Customizer.withDefaults());
        http.authenticationManager(new ProviderManager(new DaoAuthenticationProvider(scrapeUserDetailsService())));

        http.authorizeHttpRequests(authorize -> {
            // Пробы оркестратора и версия сборки — без токена, на управляющем порту их читает инфраструктура.
            authorize.requestMatchers(EndpointRequest.to("health"), EndpointRequest.to("info")).permitAll();
            if (authEnabled) {
                authorize.requestMatchers(EndpointRequest.to("prometheus")).hasRole(METRICS_ROLE);
            } else {
                authorize.requestMatchers(EndpointRequest.to("prometheus")).permitAll();
            }
            // env, heapdump, threaddump, configprops и всё прочее — закрыты,
            // даже если однажды попадут в exposure.
            authorize.anyRequest().denyAll();
        });

        return http.build();
    }

    /**
     * Единственный пользователь управляющей цепочки — сервисная учётка скрейпа. Пустые креды дают пустой список, то
     * есть аутентификация невозможна и метрики недоступны: закрытый скрейп лучше открытого.
     */
    private UserDetailsService scrapeUserDetailsService() {
        if (!scrapeProperties.hasCredentials()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(scrapeProperties.getUsername())
                .password("{noop}" + scrapeProperties.getPassword())
                .roles(METRICS_ROLE)
                .build());
    }
}

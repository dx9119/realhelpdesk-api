package com.ukhanov.realhelpdesk.core.security.keycloak.filter;

import java.io.IOException;
import java.util.Collection;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ukhanov.realhelpdesk.core.filter.LoggingFilter;
import com.ukhanov.realhelpdesk.core.observability.HttpServerRequestObservationConvention;

/**
 * Проставляет роль аутентифицированного пользователя в атрибут запроса и в MDC — то, что в локальном контуре делает
 * {@code JwtAuthFilter}.
 *
 * <p>
 * Без этого тег {@code role} метрики {@code http.server.requests} и поле {@code role} в логах остались бы
 * {@code ANONYMOUS} для всех запросов: значение читается из атрибута запроса (наблюдение останавливается уже после
 * очистки {@code SecurityContext}), а роль в keycloak-контуре выставляет Spring Security, а не фильтр приложения.
 * </p>
 *
 * <p>
 * Фильтр ставится после {@code BearerTokenAuthenticationFilter} — там контекст безопасности уже заполнен. Роль берётся
 * из authority аутентификации, то есть из БД (см. {@code KeycloakAuthenticationConverter}), а не из claim'ов токена:
 * понижение роли и блокировка пользователя действуют сразу.
 * </p>
 */
@Order(KeycloakAuthenticationRoleFilter.ORDER)
@Component
@Profile("keycloak")
public class KeycloakAuthenticationRoleFilter extends OncePerRequestFilter {

    /**
     * Порядок регистрации в servlet-контейнере: сразу после цепочки Spring Security (её фильтр идёт с {@code -100}),
     * чтобы MDC был заполнен для запроса и очищен в {@code finally} вне зависимости от того, попал ли фильтр в
     * security-цепочку.
     */
    public static final int ORDER = -99;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            applyRole(request);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(LoggingFilter.ROLE);
        }
    }

    private void applyRole(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }

        String role = roleOf(authentication.getAuthorities());
        if (role == null) {
            return;
        }

        request.setAttribute(HttpServerRequestObservationConvention.ROLE_ATTRIBUTE, role);
        // Без префикса ROLE_ — как в теге метрики и как это делает JwtAuthFilter локального контура.
        MDC.put(LoggingFilter.ROLE, HttpServerRequestObservationConvention.roleName(role));
    }

    /**
     * Первая authority с префиксом {@code ROLE_} — набор ролей приложения в проекте однозначен, служебных authority
     * нет.
     */
    private static String roleOf(Collection<? extends GrantedAuthority> authorities) {
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(HttpServerRequestObservationConvention.ROLE_PREFIX)) {
                return value;
            }
        }
        return null;
    }
}

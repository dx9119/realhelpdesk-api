package com.ukhanov.realhelpdesk.core.security.localjwt.filter;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ukhanov.realhelpdesk.core.exception.ProblemResponseWriter;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CSRF-защита для cookie-аутентификации: изменяющие запросы обязаны нести кастомный заголовок
 * {@value #REQUIRED_HEADER}.
 *
 * <p>
 * Зачем именно заголовок. Токены лежат в cookie, а {@code SameSite=None} (значение по умолчанию в
 * infrastructure/.env.dev.example) означает, что браузер отправит их и в кросс-сайтовом запросе. Классический
 * double-submit на cookie здесь не работает: фронт на другом origin не может прочитать cookie API. Заголовок же
 * закрывает вектор иначе — браузер не добавит кастомный заголовок без предварительного запроса (CORS preflight), а
 * preflight чужого origin отклоняет CORS-конфигурация (allow-list origin'ов, см. AppConfig#corsConfigurationSource).
 * Для односайтового развёртывания дополнительно достаточно SameSite=Lax.
 *
 * <p>
 * Безопасные методы (GET/HEAD/OPTIONS/TRACE) не проверяются: они не меняют состояние. Заголовок проверяется на непустое
 * значение (обычное значение — {@code XMLHttpRequest}).
 *
 * <p>
 * Профиль {@code keycloak} этот фильтр не загружает — там аутентификация через Bearer-токен, cookie не используются.
 */
@Slf4j
@RequiredArgsConstructor
@Component
@Profile("!keycloak")
public class CsrfProtectionFilter extends OncePerRequestFilter {

    public static final String REQUIRED_HEADER = "X-Requested-With";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ProblemResponseWriter problemResponseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(REQUIRED_HEADER);
        if (header != null && !header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        logger.warn("Отклонён запрос без CSRF-заголовка {}: {} {}", REQUIRED_HEADER, request.getMethod(),
                LogSanitizer.uri(request.getRequestURI()));
        problemResponseWriter.write(response, request, HttpStatus.FORBIDDEN,
                "Изменяющие запросы требуют заголовок " + REQUIRED_HEADER + " (защита cookie-аутентификации от CSRF)");
    }
}

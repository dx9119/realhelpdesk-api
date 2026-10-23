package com.ukhanov.realhelpdesk.core.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.exception.ProblemResponseWriter;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ответ на запрос без аутентификации, если он дошёл до Spring Security (основной путь закрывает JwtAuthFilter). Без
 * явного entry point Spring Security использует Http403ForbiddenEntryPoint и отвечает 403 с пустым телом — клиент не
 * может ни отличить «нет доступа» от «не аутентифицирован», ни разобрать problem+json.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProblemJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        // Через GlobalExceptionHandler этот путь не проходит, поэтому пишем сами
        logger.warn("Запрос без аутентификации: {} {}", request.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        problemResponseWriter.write(response, request, HttpStatus.UNAUTHORIZED, "Требуется аутентификация");
    }
}

package com.ukhanov.realhelpdesk.core.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.exception.ProblemResponseWriter;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Единый problem+json на отказ в доступе (403) вместо пустого тела по умолчанию. Парный к
 * {@link ProblemJsonAuthenticationEntryPoint}: клиент всегда получает разбираемый ответ и различает 401 (обновить
 * токен/логин) и 403 (нет прав).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProblemJsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemResponseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        // Отказы уровня фильтра не доходят до GlobalExceptionHandler — пишем сами
        logger.warn("Доступ запрещён: {} {}", request.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        problemResponseWriter.write(response, request, HttpStatus.FORBIDDEN,
                "Недостаточно прав для выполнения операции");
    }
}

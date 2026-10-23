package com.ukhanov.realhelpdesk.core.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Единая запись problem+json из мест, где ответ формируется вне MVC: фильтров (JwtAuthFilter, CsrfProtectionFilter),
 * интерсепторов (RateLimitInterceptor) и обработчиков Spring Security (AuthenticationEntryPoint, AccessDeniedHandler).
 *
 * <p>
 * До этого каждый такой ответ собирался вручную — строковой конкатенацией (JwtAuthFilter) или Map без обязательных
 * полей (RateLimitInterceptor). Здесь используется тот же {@link ProblemResponses}, что и в GlobalExceptionHandler,
 * поэтому формат и набор полей (type/title/status/detail/instance/timestamp) совпадают, а JSON сериализует ObjectMapper
 * — экранирование не на нашей стороне.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = ProblemResponses.of(status, detail, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    public void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String detail,
            String markerHeader)
            throws IOException {
        if (markerHeader != null) {
            response.setHeader(markerHeader, "true");
        }
        write(response, request, status, detail);
    }
}

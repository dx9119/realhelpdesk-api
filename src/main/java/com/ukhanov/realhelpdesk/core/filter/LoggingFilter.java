package com.ukhanov.realhelpdesk.core.filter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Component
public class LoggingFilter extends OncePerRequestFilter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";
    public static final String URI_REQUEST = "uriRequest";
    public static final String ROLE = "role";

    /** Значение по умолчанию: перезаписывается JwtAuthFilter после аутентификации, очищается здесь же в finally. */
    private static final String ANONYMOUS_ROLE = "ANONYMOUS";

    private final ClientIpResolver clientIpResolver;

    // ASYNC-dispatch (результат long polling) логируем отдельно: там виден итоговый статус ответа.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // query-string не кладём в MDC: в GET-параметрах бывают коды восстановления пароля.
        // Сегменты-секреты в пути (код сброса, токен подтверждения) маскируются.
        String uri = LogSanitizer.uri(request.getRequestURI());
        MDC.put(REQUEST_ID, resolveRequestId(request));
        MDC.put(CLIENT_IP, getClientIpAddress(request));
        MDC.put(URI_REQUEST, uri);
        MDC.put(ROLE, ANONYMOUS_ROLE);

        long startedNs = System.nanoTime();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            failed = true;
            logger.error("HTTP {} {} завершился исключением за {} мс", request.getMethod(), uri, elapsedMs(startedNs), ex);
            throw ex;
        } finally {
            if (!failed) {
                logExchange(request, uri, response, startedNs);
            }
            MDC.clear();
        }
    }

    // Успех — DEBUG (только профиль dev). 4xx — INFO, 5xx — ERROR: это видно в prod.
    private void logExchange(HttpServletRequest request, String uri, HttpServletResponse response, long startedNs) {
        int status = response.getStatus();
        long tookMs = elapsedMs(startedNs);
        if (status >= 500) {
            logger.error("HTTP {} {} -> {} за {} мс", request.getMethod(), uri, status, tookMs);
        } else if (status >= 400) {
            logger.info("HTTP {} {} -> {} за {} мс", request.getMethod(), uri, status, tookMs);
        } else {
            logger.debug("HTTP {} {} -> {} за {} мс", request.getMethod(), uri, status, tookMs);
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    // Чужой X-Request-ID принимаем только как безопасный идентификатор, иначе подменяем своим.
    // Решение сохраняется в атрибуте запроса, чтобы ASYNC-dispatch логировался под тем же id.
    private static String resolveRequestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID);
        if (existing instanceof String id && SAFE_REQUEST_ID.matcher(id).matches()) {
            return id;
        }
        String header = request.getHeader("X-Request-ID");
        String resolved = header != null && SAFE_REQUEST_ID.matcher(header).matches() ? header : Long.toUnsignedString(RANDOM.nextLong());
        request.setAttribute(REQUEST_ID, resolved);
        return resolved;
    }

    // Один резолвер с рейт-лимитом (см. ClientIpResolver): лог и лимит видят один и тот же адрес клиента.
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        return ip == null || ip.isBlank() ? "ip null" : ip;
    }

}

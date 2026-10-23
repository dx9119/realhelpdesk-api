package com.ukhanov.realhelpdesk.core.security.ratelimit.interceptor;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.ukhanov.realhelpdesk.core.exception.ProblemResponseWriter;
import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;
import com.ukhanov.realhelpdesk.core.security.ratelimit.config.RateLimitProperties;
import com.ukhanov.realhelpdesk.core.security.ratelimit.service.RateLimitService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final ProblemResponseWriter problemResponseWriter;
    private final RateLimitProperties rateLimitProperties;
    private final ClientIpResolver clientIpResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        RateLimitProperties.Limit limit = rateLimitProperties.require(rateLimit.key());

        String client = clientIpResolver.resolve(request);
        // ключ — по шаблону маршрута: /password-resets/123 и /password-resets/456 должны считаться одним лимитом
        String key = client + "|" + request.getMethod() + " " + routeOf(request);
        RateLimitService.Decision decision = rateLimitService.check(key, limit.requests(),
                Duration.ofSeconds(limit.windowSeconds()));

        if (decision.allowed()) {
            return true;
        }

        logger.warn("Превышен лимит запросов: {} {}, ip={}", request.getMethod(),
                LogSanitizer.uri(request.getRequestURI()), client);

        String detail = "Слишком много запросов. Повторите через " + decision.retryAfterSeconds() + " сек.";
        // Retry-After — до записи тела: после writeValue заголовки уже отправлены
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        problemResponseWriter.write(response, request, HttpStatus.TOO_MANY_REQUESTS, detail);

        return false;
    }

    private String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}

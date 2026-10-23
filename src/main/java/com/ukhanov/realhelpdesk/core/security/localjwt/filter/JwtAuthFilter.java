package com.ukhanov.realhelpdesk.core.security.localjwt.filter;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ukhanov.realhelpdesk.core.exception.ProblemResponseWriter;
import com.ukhanov.realhelpdesk.core.filter.LoggingFilter;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.observability.HttpServerRequestObservationConvention;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenBearerResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.AccessTokenAuthService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.DecodeTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.ValidTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.WhiteUrlConfig;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Фильтр аутентификации по локальному JWT. Не загружается в профиле {@code keycloak} — там токены проверяет OAuth2
 * Resource Server (Keycloak).
 */
@Slf4j
@RequiredArgsConstructor
@Component
@Profile("!keycloak")
public class JwtAuthFilter extends OncePerRequestFilter {

    private final ValidTokenService validTokenService;
    private final DecodeTokenService decodeTokenService;
    private final AccessTokenAuthService accessTokenAuthService;
    private final AntPathMatcher antPathMatcher;
    private final ProblemResponseWriter problemResponseWriter;
    // Результат long polling приходит через ASYNC-dispatch того же запроса: без повторной
    // аутентификации security-цепочка не найдёт контекст и закроет готовый ответ 403.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();

        // пропускаем OPTIONS для CORS запросов
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Пропускаем фильтр для URL из БС
        if (WhiteUrlConfig.WHITE_LIST_URLS.stream()
                .anyMatch(whiteListedPath -> antPathMatcher.match(whiteListedPath, path))) {

            filterChain.doFilter(request, response);
            return;
        }

        // Парсим access-токен
        TokenBearerResponse token = resolveToken(request);
        if (token == null) {
            logger.debug("Нет access-токена: {}", LogSanitizer.uri(path));
            unauthorized(request, response, "Требуется аутентификация", "X-Auth-Token-Missing");
            return;
        }

        try {
            // Проверяем подпись, срок, issuer/audience и тип токена
            validTokenService.lowLevelVerifyToken(token, JwtClaims.TYPE_ACCESS);

            Claims claims = decodeTokenService.decodeJwtClaims(token);

            // Проверяем пользователя в БД: статус и версию токена (отзыв)
            UserModel user = accessTokenAuthService.loadVerifiedUser(claims);

            // Аутентификация пользователя (роль берём из БД, а не из токена)
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user.getId().toString(),
                    null,
                    List.of(new SimpleGrantedAuthority(user.getUserRole().name())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Роль в атрибут запроса: к моменту остановки наблюдения http.server.requests
            // SecurityContext уже очищен, а тег role обязан отражать именно этот запрос.
            request.setAttribute(HttpServerRequestObservationConvention.ROLE_ATTRIBUTE, user.getUserRole().name());
            // Роль в MDC для логов запроса (без префикса ROLE_, как в теге метрики); default ANONYMOUS
            // положил LoggingFilter, очистку MDC делает он же в finally.
            MDC.put(LoggingFilter.ROLE, HttpServerRequestObservationConvention.roleName(user.getUserRole().name()));

            // Следующий фильтр
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            logger.debug("Access-токен истёк: {}", LogSanitizer.uri(path));
            unauthorized(request, response, "Срок действия токена истек", "X-Access-Token-Expired");

        } catch (MalformedJwtException e) {
            logger.warn("Некорректный access-токен: {}", LogSanitizer.uri(path));
            unauthorized(request, response, "Некорректный формат токена", "X-Malformed-Token");

        } catch (JwtException e) {
            logger.warn("Ошибка проверки access-токена: {}", LogSanitizer.uri(path));
            unauthorized(request, response, "Некорректный или недействительный токен", "X-Invalid-Token");

        } catch (TokenException e) {
            logger.warn("Access-токен отклонён: {} ({})", LogSanitizer.uri(path), e.getClass().getSimpleName());
            unauthorized(request, response, e.getMessage(), "X-Verify-Token-Failed");
        }
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response, String detail,
            String markerHeader)
            throws IOException {
        // Ответ пишет ProblemResponseWriter, а не локальная конкатенация строк: detail с кавычкой
        // ломает JSON, а timestamp обязан совпадать с форматом остальных ошибок
        problemResponseWriter.write(response, request, HttpStatus.UNAUTHORIZED, detail, markerHeader);
    }

    // Извлекаем токен из куки
    private TokenBearerResponse resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("accessToken".equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return new TokenBearerResponse(cookie.getValue());
                }
            }
        }
        return null;
    }
}

package com.ukhanov.realhelpdesk.core.security.localjwt.auth.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.time.Duration;

import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.dto.SessionResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.dto.LoginRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service.LoginService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.logout.exception.LogoutException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.logout.service.LogoutService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.refresh.exception.RefreshException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.refresh.service.RefreshService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.dto.RegisterRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.exception.RegistrationException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.service.RegistrationService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenStatusResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtProperties;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;
import com.ukhanov.realhelpdesk.feature.usermanager.service.UserManageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
@Profile("!keycloak")
public class AuthController {

    private static final String ACCESS_COOKIE = "accessToken";
    private static final String REFRESH_COOKIE = "refreshToken";
    private static final URI PROFILE_URI = URI.create("/api/v1/users/profile");

    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    // Refresh-cookie нужен только эндпоинтам этого контроллера — не отправляем его на весь API
    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final LogoutService logoutService;
    private final GetTokenService getTokenService;
    private final RefreshService refreshService;
    private final JwtProperties jwtProperties;
    private final UserManageService userManageService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Регистрация нового пользователя",
            description = "Создаёт аккаунт, выпускает токены и устанавливает cookies.")
    @PostMapping("/register")
    @RateLimit(key = "auth-register")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<Void> registration(@Valid @RequestBody RegisterRequest registerRequest,
            HttpServletRequest httpRequest)
            throws RegistrationException, MessagingException, EmailAccessDeniedException, CaptchaException,
            UnsupportedEncodingException {

        TokensResponse tokens = registrationService.processRegistration(registerRequest,
                clientIpResolver.resolve(httpRequest));

        return ResponseEntity.created(PROFILE_URI)
                .header(HttpHeaders.SET_COOKIE, accessCookie(tokens.accessToken(), accessTokenTtl()).toString(),
                        refreshCookie(tokens.refreshToken(), refreshTokenTtl()).toString())
                .build();
    }

    @Operation(summary = "Вход пользователя",
            description = "Проверяет учётные данные, выпускает токены и устанавливает cookies.")
    @PostMapping("/login")
    @RateLimit(key = "auth-login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest loginRequest) throws TokenException {
        TokensResponse tokens = loginService.processLogin(loginRequest);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie(tokens.accessToken(), accessTokenTtl()).toString(),
                        refreshCookie(tokens.refreshToken(), refreshTokenTtl()).toString())
                .build();
    }

    @Operation(summary = "Получение нового access-токена",
            description = "Ротирует refresh-токен и выпускает новую пару access/refresh.")
    @PostMapping("/tokens/access")
    @RateLimit(key = "auth-access-token")
    public ResponseEntity<Void> createAccessToken(HttpServletRequest request) throws TokenException, RefreshException {

        ResponseCookie accessCookie = accessCookie(refreshService.updateAccess(request), accessTokenTtl());

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, accessCookie.toString()).build();
    }

    @Operation(summary = "Статус refresh-токена",
            description = "Проверяет, активен ли refresh-токен в cookie, и возвращает его createdAt.")
    @GetMapping("/tokens/refresh")
    public ResponseEntity<TokenStatusResponse> refreshTokenStatus(HttpServletRequest request)
            throws TokenException, LogoutException {
        TokenStatusResponse response = getTokenService.getStatusRefreshTokenFromCookie(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Проверка текущей сессии",
            description = "Возвращает признак аутентификации и профиль пользователя без дополнительного запроса.")
    @GetMapping("/session")
    public ResponseEntity<SessionResponse> sessionStatus(HttpServletRequest request) throws TokenException {
        SessionResponse response = new SessionResponse(true, userManageService.getUserInfo());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Выход из системы", description = "Отозывает refresh-токен и удаляет cookies.")
    @DeleteMapping("/session")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> logout(HttpServletRequest request) throws TokenException, LogoutException {

        logoutService.processLogout(request);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, accessCookie("", Duration.ZERO).toString(),
                        refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    // Max-Age cookie берём из конфига, а не из константы: он обязан совпадать со сроком жизни токена,
    // иначе cookie переживёт токен или исчезнет раньше него
    private Duration accessTokenTtl() {
        return Duration.ofSeconds(jwtProperties.getAccessTokenExpirationSeconds());
    }

    private Duration refreshTokenTtl() {
        return Duration.ofSeconds(jwtProperties.getRefreshTokenExpirationSeconds());
    }

    private ResponseCookie accessCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(maxAge)
                .sameSite(jwtProperties.getCookie().getSameSite().getValue());
        return withDomain(builder).build();
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .sameSite(jwtProperties.getCookie().getSameSite().getValue());
        return withDomain(builder).build();
    }

    // Пустой jwt.cookie.domain — атрибут Domain не ставим, cookie остаётся host-only
    private ResponseCookie.ResponseCookieBuilder withDomain(ResponseCookie.ResponseCookieBuilder builder) {
        String domain = jwtProperties.getCookie().getDomain();
        return domain != null ? builder.domain(domain) : builder;
    }

}

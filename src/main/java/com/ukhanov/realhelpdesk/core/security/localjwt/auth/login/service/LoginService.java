package com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.observability.AuthMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.dto.LoginRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.user.SecurityUser;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.CustomUserDetailsService;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoginService {

    private final PasswordEncoder passwordEncoder;
    private final GetTokenService getTokenService;
    private final UserDomainService userDomainService;
    private final CustomUserDetailsService customUserDetailsService;
    private final AuthMetrics authMetrics;
    // Проверяем, если пользователь существует и пароль правильный - отдаем токены
    // Аутентификация/Аутентификация происходит в фильтре JwtAuthFilter
    public TokensResponse processLogin(LoginRequest loginRequest) throws TokenException {
        if (!userDomainService.isUserExistsByEmail(loginRequest.email())) {
            // Адрес в лог не пишем: это PII и данные, которые присылает клиент (в том числе злоумышленник).
            logger.warn("Отказ во входе: пользователь не найден");
            authMetrics.record(AuthMetrics.TYPE_LOGIN, AuthMetrics.RESULT_FAILURE);
            throw new UsernameNotFoundException(
                    "Ошибка авторизации: неверный логин, пароль или отсутствующий пользователь");
        }

        SecurityUser user = customUserDetailsService.loadUserByUsername(loginRequest.email());
        Long userId = user.getOriginalUser().getId();

        if (user.getOriginalUser().getUserStatus() != UserStatus.ACTIVE) {
            logger.warn("Отказ во входе: учётная запись не активна, userId={}", userId);
            authMetrics.record(AuthMetrics.TYPE_LOGIN, AuthMetrics.RESULT_LOCKED);
            throw new BadCredentialsException(
                    "Ошибка авторизации: неверный логин, пароль или отсутствующий пользователь");
        }

        if (!isPasswordValid(loginRequest.password(), user.getPassword())) {
            logger.warn("Отказ во входе: неверный пароль, userId={}", userId);
            authMetrics.record(AuthMetrics.TYPE_LOGIN, AuthMetrics.RESULT_FAILURE);
            throw new BadCredentialsException(
                    "Ошибка авторизации: неверный логин, пароль или отсутствующий пользователь");

        }

        // Ротация: при каждом входе выдаём свежий refresh-токен (в БД хранится только его хеш).
        // Успешный вход логируем всегда, чтобы первый вход после регистрации не оставался без следа.
        TokensResponse tokens = getTokenService.getNewTokens(user.getOriginalUser());
        logger.info("Вход выполнен, userId={}", userId);
        authMetrics.record(AuthMetrics.TYPE_LOGIN, AuthMetrics.RESULT_SUCCESS);
        return tokens;
    }

    public boolean isPasswordValid(String password, String encodedPassword) {
        return passwordEncoder.matches(password, encodedPassword);
    }

}

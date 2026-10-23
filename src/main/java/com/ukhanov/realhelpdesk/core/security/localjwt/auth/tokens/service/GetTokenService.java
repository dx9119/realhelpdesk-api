package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenBearerResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenStatusResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.Token;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenBearer;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;
import com.ukhanov.realhelpdesk.core.security.user.SecurityUser;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class GetTokenService {

    private final GenTokenService genTokenService;
    private final SaveTokenService saveTokenService;
    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;
    private final FindTokenService findTokenService;
    private final ValidTokenService validTokenService;
    private final DecodeTokenService decodeTokenService;
    // Новые токены: access + свежий refresh. Ротация при каждом входе/регистрации:
    // предыдущие активные refresh-токены пользователя отзываются, поэтому старый
    // токен перестаёт быть валидным сразу после выдачи нового.
    @Transactional
    public TokensResponse getNewTokens(UserModel user) {
        SecurityUser securityUser = new SecurityUser(user);

        TokenBearerResponse tokenBearerResponse = genTokenService.generateAccessJwtToken(securityUser);
        RefreshTokenModel refreshToken = genTokenService.generateRefreshJwtToken(securityUser);

        int revoked = revokeActiveRefreshTokens(user, TokenStatus.REVOKED);
        if (revoked > 0) {
            logger.info("Ротация refresh-токенов: отозвано {}, userId={}", revoked, user.getId());
        }
        saveTokenService.saveRefreshToken(refreshToken);

        return new TokensResponse(tokenBearerResponse.token(), refreshToken.getRawToken(), securityUser.getUsername());
    }

    /**
     * Отзыв активных refresh-токенов пользователя bulk-UPDATE одним запросом (а не N селектов + N сохранений):
     * nextStatus — REVOKED при ротации, PASSWD_CHANGE при смене пароля.
     */
    @Transactional
    public int revokeActiveRefreshTokens(UserModel user, TokenStatus nextStatus) {
        Objects.requireNonNull(user, "Пользователь не может быть null!");

        return jwtRefreshTokenRepository.updateStatusByEmailAndStatus(user.getEmail(), TokenStatus.ACTIVE, nextStatus);
    }

    private Token extractAndValidateToken(HttpServletRequest request, String tokenName) throws TokenException {
        Objects.requireNonNull(request, "Запрос не может быть null");

        Token token = new Token(decodeTokenService.extractTokenFromCookies(request, tokenName));
        if (token.getToken() == null || token.getToken().isEmpty()) {
            throw new TokenException("Cookie с именем " + tokenName + " не найдена");
        }

        return token;
    }

    public TokenStatusResponse getStatusRefreshTokenFromCookie(HttpServletRequest request) throws TokenException {
        Token tokenRefresh = extractAndValidateToken(request, "refreshToken");
        RefreshTokenModel refreshToken = findTokenService.findRefreshToken(tokenRefresh);

        return new TokenStatusResponse(refreshToken.getStatus(), refreshToken.getCreatedAt());
    }

    // Новый access по валидному активному refresh-токену
    public TokenBearerResponse getNewAccessToken(TokenBearer tokenRefresh) throws TokenException {
        Objects.requireNonNull(tokenRefresh, "Токен не может быть null");

        RefreshTokenModel refreshToken = findTokenService.findRefreshToken(tokenRefresh);

        if (refreshToken.getStatus() != TokenStatus.ACTIVE) {
            throw new TokenException("Токен обновления не активен", null);
        }

        validTokenService.lowLevelVerifyToken(tokenRefresh, JwtClaims.TYPE_REFRESH);

        UserModel user = refreshToken.getUser();
        // Успешное обновление access-токена по refresh: видно в dev, отказ логирует advice (WARN).
        // Идентификатор здесь доступен, поэтому пишем его, а не строку без контекста (audit B9).
        logger.debug("Access-токен обновлён, userId={}", user.getId());
        return genTokenService.generateAccessJwtToken(new SecurityUser(user));
    }
}

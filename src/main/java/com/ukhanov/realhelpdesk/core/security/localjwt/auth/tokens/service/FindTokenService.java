package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenBearer;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.TokenHasher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class FindTokenService {

    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;
    // В БД хранится SHA-256 хеш токена
    public RefreshTokenModel findRefreshToken(TokenBearer token) throws TokenException {
        Objects.requireNonNull(token, "Токен не может быть null!");
        Objects.requireNonNull(token.getToken(), "Значение токена не может быть null!");

        RefreshTokenModel refreshTokenModel = jwtRefreshTokenRepository
                .findByTokenRefresh(TokenHasher.sha256(token.getToken()))
                .orElseThrow(() -> {
                    return new TokenException("Токен обновления не найден", null);
                });

        logger.debug("Токен обновления найден: id={}, статус={}", refreshTokenModel.getUuid(),
                refreshTokenModel.getStatus());
        return refreshTokenModel;
    }
}

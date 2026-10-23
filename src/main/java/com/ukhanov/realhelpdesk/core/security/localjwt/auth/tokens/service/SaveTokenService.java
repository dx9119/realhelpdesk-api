package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class SaveTokenService {

    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;

    public RefreshTokenModel saveRefreshToken(RefreshTokenModel token) {
        Objects.requireNonNull(token, "RefreshTokenModel не может быть null");
        RefreshTokenModel saved = jwtRefreshTokenRepository.save(token);
        // След сохранения — по id записи: значения токена и его хеша в лог не идут
        logger.debug("Сохранён {} токен, userId={}, tokenId={}", JwtClaims.TYPE_REFRESH, saved.getUser().getId(),
                saved.getUuid());
        return saved;
    }

}

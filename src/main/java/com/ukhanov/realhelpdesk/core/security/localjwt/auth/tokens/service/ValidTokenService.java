package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenBearer;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtConfig;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class ValidTokenService {

    private final JwtConfig jwtConfig;
    private final DecodeTokenService decodeTokenService;

    public void lowLevelVerifyToken(TokenBearer token, String expectedType) throws TokenException {
        Claims claims = decodeTokenService.decodeJwtClaims(token);

        // Тип токена: refresh нельзя подсунуть вместо access и наоборот
        String actualType = claims.get(JwtClaims.TYPE, String.class);
        if (!expectedType.equals(actualType)) {
            logger.debug("Недопустимый тип токена: {}", actualType);
            throw new TokenException("Недопустимый тип токена", null);
        }

        Instant expiration = Optional.ofNullable(claims.getExpiration())
                .map(Date::toInstant)
                .orElseThrow(() -> new TokenException("Отсутствует claim 'expiration'", null));

        Set<String> actual = claims.getAudience();
        if (!jwtConfig.getAudience().equals(actual)) {
            logger.debug("Недопустимое значение audience: {}", actual);
            throw new TokenException("Несовпадение значения audience", null);
        }

        if (expiration.isBefore(Instant.now())) {
            logger.debug("Срок действия токена истёк: {}", expiration);
            throw new TokenException("Срок действия токена истёк", null);
        }

        String tokenIssuer = claims.getIssuer();
        if (!jwtConfig.getIssuer().equals(tokenIssuer)) {
            logger.debug("Недопустимый издатель токена: {}", tokenIssuer);
            throw new TokenException("Недопустимый издатель токена", null);
        }
    }
}

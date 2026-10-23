package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.util.Objects;

import javax.crypto.SecretKey;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenBearer;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class DecodeTokenService {

    private final JwtConfig jwtConfig;

    public Claims decodeJwtClaims(TokenBearer token) throws JwtException {
        Objects.requireNonNull(token, "Токен не может быть null!");

        // jjwt на пустой строке бросает IllegalArgumentException — нормализуем в JwtException (обрабатывается фильтром)
        if (token.getToken() == null || token.getToken().isBlank()) {
            throw new MalformedJwtException("Токен отсутствует или пуст");
        }

        Claims claims = Jwts.parser()
                .verifyWith((SecretKey) jwtConfig.getJwtKey())
                .build()
                .parseSignedClaims(token.getToken())
                .getPayload();

        // Обязательные поля
        if (claims.getSubject() == null) {
            throw new JwtException("Отсутствует claim 'subject' в токене");
        }

        logger.debug("Разобран {} токен, userId={}, jti={}", claims.get(JwtClaims.TYPE), claims.getSubject(),
                claims.getId());

        return claims;
    }

    public String extractTokenFromCookies(HttpServletRequest request, String cookieName) throws TokenException {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            throw new TokenException("В запросе не получены cookies");
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new TokenException("В запросе не найдена cookie с именем '" + cookieName + "'");
    }
}

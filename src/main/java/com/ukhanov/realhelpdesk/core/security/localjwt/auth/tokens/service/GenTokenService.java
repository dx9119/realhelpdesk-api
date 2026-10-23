package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenBearerResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.JwtClaims;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.utils.TokenHasher;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtConfig;
import com.ukhanov.realhelpdesk.core.security.user.SecurityUser;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class GenTokenService {

    private final JwtConfig jwtConfig;

    public TokenBearerResponse generateAccessJwtToken(SecurityUser securityUser) {
        Objects.requireNonNull(securityUser, "SecurityUser не может быть null!");
        Objects.requireNonNull(securityUser.getRule(), "Роль у SecurityUser не может отсутствовать!");

        Instant dateNow = Instant.now();
        Instant expiry = dateNow.plusSeconds(jwtConfig.getAccessTokenExpirationSeconds());

        // jti гарантирует уникальность: два токена, выданные в одну секунду, иначе байт-в-байт одинаковы.
        // Значение токена в лог не идёт — след выпуска оставляем по userId и jti
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(jwtConfig.getIssuer())
                .subject(securityUser.getId())
                .id(jti)
                .expiration(Date.from(expiry))
                .issuedAt(Date.from(dateNow))
                .claim(JwtClaims.TYPE, JwtClaims.TYPE_ACCESS)
                .claim(JwtClaims.TOKEN_VERSION, securityUser.getOriginalUser().getTokenVersion())
                .claim(JwtClaims.ROLE, securityUser.getRule())
                .claim("aud", jwtConfig.getAudience()) // вместо audience().add
                .signWith((SecretKey) jwtConfig.getJwtKey())
                .compact();

        logger.debug("Выдан {} токен, userId={}, jti={}", JwtClaims.TYPE_ACCESS, securityUser.getId(), jti);

        return new TokenBearerResponse(token);
    }

    public RefreshTokenModel generateRefreshJwtToken(SecurityUser securityUser) {
        Objects.requireNonNull(securityUser, "SecurityUser не может быть null!");

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtConfig.getRefreshTokenExpirationSeconds());

        // Уникальный id обязателен: в БД хеш токена хранится с unique-ограничением.
        // В лог — только userId и jti, сам токен и его хешь не пишем
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(jwtConfig.getIssuer())
                .subject(securityUser.getId())
                .id(jti)
                .audience()
                .add(jwtConfig.getAudience())
                .and()
                .expiration(Date.from(expiry))
                .issuedAt(Date.from(now))
                .claim(JwtClaims.TYPE, JwtClaims.TYPE_REFRESH)
                .signWith((SecretKey) jwtConfig.getJwtKey())
                .compact();

        RefreshTokenModel jwtRefreshTokenModel = new RefreshTokenModel();
        jwtRefreshTokenModel.setUser(securityUser.getOriginalUser());
        jwtRefreshTokenModel.setStatus(TokenStatus.ACTIVE);
        // В БД — только хеш, сырой токен отдаём клиенту через rawToken
        jwtRefreshTokenModel.setToken(TokenHasher.sha256(token));
        jwtRefreshTokenModel.setRawToken(token);

        logger.debug("Выдан {} токен, userId={}, jti={}", JwtClaims.TYPE_REFRESH, securityUser.getId(), jti);

        return jwtRefreshTokenModel;
    }

}

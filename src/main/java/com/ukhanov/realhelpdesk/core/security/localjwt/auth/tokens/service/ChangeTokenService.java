package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.Token;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenBearer;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class ChangeTokenService {

    private final SaveTokenService saveTokenService;
    private final DecodeTokenService decodeTokenService;
    private final FindTokenService findTokenService;

    public RefreshTokenModel changeStatusRefreshToken(HttpServletRequest request, TokenStatus newStatus)
            throws TokenException {
        TokenBearer tokenBearer = new Token(decodeTokenService.extractTokenFromCookies(request, "refreshToken"));
        RefreshTokenModel refreshToken = findTokenService.findRefreshToken(tokenBearer);
        TokenStatus previousStatus = refreshToken.getStatus();
        refreshToken.setStatus(newStatus);
        logger.debug("Статус refresh-токена {} → {}", previousStatus, newStatus);

        return saveTokenService.saveRefreshToken(refreshToken);
    }
}

package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenCleanupResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.RefreshTokenCleanupService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.RefreshTokenCleanupService.CleanupResult;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/operator/tokens")
public class TokenOperatorController {

    private final RefreshTokenCleanupService refreshTokenCleanupService;

    @Operation(summary = "Очистка отозванных refresh-токенов",
            description = "Удаляет отозванные токены старше "
                    + "JWT_REVOKED_TOKEN_RETENTION_SECONDS. Тот же механизм, что у ночного расписания: оценка объёма "
                    + "и удаление батчами "
                    + "с адаптивным размером и паузами, чтобы не создавать пиковой нагрузки на БД.")
    @PostMapping("/cleanup")
    @PreAuthorize("hasRole('OPERATOR')")
    public TokenCleanupResponse cleanupRevokedTokens() {
        CleanupResult result = refreshTokenCleanupService.cleanRevokedTokens();
        return new TokenCleanupResponse(result.deleted(), result.batches());
    }
}

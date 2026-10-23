package com.ukhanov.realhelpdesk.core.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.security.ratelimit.service.RateLimitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@RequiredArgsConstructor
public class DevOpsController {

    private final RateLimitService rateLimitService;

    @Operation(summary = "Сброс рейт-лимитов (dev)",
            description = "Dev-only утилита для e2e: сброс in-memory бакетов, чтобы повторный "
                    + "прогон не упирался в 429 от предыдущего. Контроллер существует только в профиле dev; "
                    + "путь в белых списках — "
                    + "e2e вызывает его до аутентификации.")
    @PostMapping("/ratelimit-reset")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> resetRateLimits() {
        rateLimitService.reset();
        logger.info("Rate-limit бакеты сброшены (dev-эндпоинт)");
        return ResponseEntity.noContent().build();
    }
}

package com.ukhanov.realhelpdesk.core.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.security.ratelimit.service.RateLimitService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-only утилита для e2e-сценариев: сброс in-memory бакетов рейт-лимитов, чтобы повторный make e2e-* не упирался в 429 от предыдущего
 * прогона (без make restart).
 *
 * <p>
 * Контроллер существует только в профиле dev — в рабочем контуре эндпоинт отсутствует (404). Путь включён в белые списки (WhiteUrlConfig /
 * KeycloakWhiteUrlConfig): e2e вызывает его до аутентификации.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@RequiredArgsConstructor
public class DevOpsController {

    private final RateLimitService rateLimitService;

    @PostMapping("/ratelimit-reset")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> resetRateLimits() {
        rateLimitService.reset();
        logger.info("Rate-limit бакеты сброшены (dev-эндпоинт)");
        return ResponseEntity.noContent().build();
    }
}

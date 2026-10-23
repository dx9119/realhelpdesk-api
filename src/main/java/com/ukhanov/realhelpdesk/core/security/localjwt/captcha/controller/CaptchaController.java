package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.ChallengeRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.ChallengeResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.SolveRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.SolveResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaService;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/captcha")
public class CaptchaController {

    private final CaptchaService captchaService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Получить задание капчи",
            description = "Возвращает challenge выбранного действия (pow либо emoji) с параметрами "
                    + "решения и сроком жизни. Уровень сложности выбирает сервер; провалы одного IP повышают "
                    + "difficulty, оставаясь в pow.")
    @PostMapping("/challenge")
    @RateLimit(key = "captcha-challenge")
    public ResponseEntity<ChallengeResponse> challenge(@Valid @RequestBody ChallengeRequest request,
            HttpServletRequest httpRequest)
            throws CaptchaException {
        return ResponseEntity.ok(captchaService.challenge(request.action(), clientIpResolver.resolve(httpRequest)));
    }

    @Operation(summary = "Решить задание капчи",
            description = "Проверяет nonce (pow) либо индексы ячеек (emoji) и выдаёт captchaToken "
                    + "для последующего действия. Провал — 400 problem+json с машинным полем code.")
    @PostMapping("/solve")
    @RateLimit(key = "captcha-solve")
    public ResponseEntity<SolveResponse> solve(@Valid @RequestBody SolveRequest request, HttpServletRequest httpRequest)
            throws CaptchaException {
        return ResponseEntity
                .ok(captchaService.solve(request.challengeId(), request.nonce(), request.answer(),
                        clientIpResolver.resolve(httpRequest)));
    }
}

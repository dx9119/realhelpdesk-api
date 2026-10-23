package com.ukhanov.realhelpdesk.core.mail.controller;

import java.io.UnsupportedEncodingException;

import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.mail.dto.EmailInfoResponse;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailPolicyService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaTokenService;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/email")
public class EmailPolicyController {

    private final EmailDeliveryService emailDeliveryService;
    private final EmailPolicyService emailPolicyService;
    private final CaptchaService captchaService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Подтверждение email",
            description = "Подтверждает email по токену из письма. Возвращает 204 без тела.")
    @PostMapping("/confirmations/{token}")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> confirmEmail(@PathVariable Long token) throws EmailAccessDeniedException {
        emailDeliveryService.confirmEmail(token);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Уровень email-оповещений",
            description = "Возвращает текущий уровень mute для in-app оповещений.")
    @GetMapping("/info")
    public ResponseEntity<EmailInfoResponse> getInfo() {
        EmailInfoResponse response = emailPolicyService.getEmailInfo();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Отписка от email-уведомлений",
            description = "Добавляет событие в список стоп-лист. Возвращает 204 без тела.")
    @DeleteMapping("/notifications/{event}")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> stopNotifications(@PathVariable NotificationEvent event) {
        emailPolicyService.addToStopList(event);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Запрос кода подтверждения email",
            description = "Отправляет код подтверждения на текущий email клиента. "
                    + "Опционально принимает X-Captcha-Token. Возвращает 202 — код отправляется асинхронно.")
    @PostMapping("/codes")
    @RateLimit(key = "email-code")
    @ApiResponse(responseCode = "202", description = "Принято в обработку")
    public ResponseEntity<Void> sendConfirmCode(
            @RequestHeader(value = "X-Captcha-Token", required = false) String captchaToken,
            HttpServletRequest httpRequest) throws MessagingException, CaptchaException, UnsupportedEncodingException {
        captchaService.verify(captchaToken, CaptchaTokenService.ACTION_EMAIL_CODE,
                clientIpResolver.resolve(httpRequest));
        emailDeliveryService.sendConfirmCode();
        return ResponseEntity.accepted().build();
    }

}

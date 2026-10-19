package com.ukhanov.realhelpdesk.feature.usermanager.controller;

import java.io.UnsupportedEncodingException;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.NewPasswdRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.PatchUserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.RecoveryRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;
import com.ukhanov.realhelpdesk.feature.usermanager.service.UserManageService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserManageService userManageService;

    @GetMapping("/profile")
    public ResponseEntity<UserInfoResponse> getUserInfo() {

        UserInfoResponse response = userManageService.getUserInfo();

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }

    /** Полная замена профиля: If-Match с версией из GET не даёт затереть чужое изменение. */
    @PutMapping("/profile")
    public ResponseEntity<UserInfoResponse> updateUserInfo(@Valid @RequestBody UserInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws UserManageException {

        UserInfoResponse response = userManageService.updateUserInfo(request, ifMatch);

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }

    /** Частичное обновление: меняются только переданные поля. */
    @PatchMapping("/profile")
    public ResponseEntity<UserInfoResponse> patchUserInfo(@Valid @RequestBody PatchUserInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws UserManageException {

        UserInfoResponse response = userManageService.patchUserInfo(request, ifMatch);

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }

    /**
     * Запрос восстановления пароля: письмо отправляется синхронно, поэтому 202 был бы обещанием «сделаем потом» — клиент не показал бы
     * ошибку SMTP. Отвечаем 204, как остальные команды.
     */
    @PostMapping("/password-resets")
    @RateLimit(key = "password-reset-request")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody RecoveryRequest request)
            throws MessagingException, UnsupportedEncodingException {
        userManageService.sendResetLink(request);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password-resets/{code}")
    @RateLimit(key = "password-reset-confirm")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> resetPassword(@PathVariable Long code, @Valid @RequestBody NewPasswdRequest request) throws TokenException {
        userManageService.setNewPasswd(code, request);

        return ResponseEntity.noContent().build();
    }
}

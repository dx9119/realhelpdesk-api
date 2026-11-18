package com.ukhanov.realhelpdesk.feature.usermanager.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.PatchUserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;
import com.ukhanov.realhelpdesk.feature.usermanager.service.UserManageService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserManageService userManageService;

    @Operation(summary = "Профиль пользователя",
            description = "Возвращает профиль текущего пользователя с ETag для последующего If-Match.")
    @GetMapping("/profile")
    public ResponseEntity<UserInfoResponse> getUserInfo() {

        UserInfoResponse response = userManageService.getUserInfo();

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }

    @Operation(summary = "Полная замена профиля",
            description = "Заменяет профиль целиком. If-Match с версией из GET не даёт затереть чужое изменение.")
    @PutMapping("/profile")
    public ResponseEntity<UserInfoResponse> updateUserInfo(@Valid @RequestBody UserInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws UserManageException {

        UserInfoResponse response = userManageService.updateUserInfo(request, ifMatch);

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }

    @Operation(summary = "Частичное обновление профиля",
            description = "Меняет только переданные поля (null — не трогать). "
                    + "If-Match с версией из GET обязателен для защиты от потери чужого изменения.")
    @PatchMapping("/profile")
    public ResponseEntity<UserInfoResponse> patchUserInfo(@Valid @RequestBody PatchUserInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws UserManageException {

        UserInfoResponse response = userManageService.patchUserInfo(request, ifMatch);

        return ResponseEntity.ok().eTag(EntityTag.of("user", response.version())).body(response);
    }
}

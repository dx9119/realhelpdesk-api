package com.ukhanov.realhelpdesk.core.security.localjwt.auth.dto;

import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;

import io.swagger.v3.oas.annotations.media.Schema;

public record SessionResponse(
        @Schema(description = "Аутентифицирован ли пользователь", example = "true") boolean authenticated,

        @Schema(description = "Профиль аутентифицированного пользователя (без отдельного GET /users/profile)",
                implementation = UserInfoResponse.class) UserInfoResponse user) {
}

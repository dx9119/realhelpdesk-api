package com.ukhanov.realhelpdesk.feature.usermanager.dto;

import java.time.Instant;

import com.ukhanov.realhelpdesk.core.security.user.model.UserPlatformSource;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Профиль пользователя, возвращаемый GET /users/profile")
public record UserInfoResponse(@Schema(description = "Внутренний идентификатор", example = "1") Long id,

        @Schema(description = "Внешний идентификатор (Keycloak / SSO)", example = "abc-123") Long externalId,

        @Schema(description = "Имя", example = "Иван") String firstName,

        @Schema(description = "Фамилия", example = "Иванов") String lastName,

        @Schema(description = "Отчество", example = "Иванович") String middleName,

        @Schema(description = "Дополнительная информация", example = "Менеджер") String additionalInfo,

        @Schema(description = "Email", example = "ivan@example.com") String email,

        @Schema(description = "Email подтверждён", example = "true") boolean emailVerified,

        @Schema(description = "Роль пользователя", example = "USER") UserRole userRole,

        @Schema(description = "Статус аккаунта", example = "ACTIVE") UserStatus userStatus,

        @Schema(description = "Источник платформы", example = "KEYCLOAK") UserPlatformSource userPlatformSource,

        @Schema(description = "Дата создания", example = "2024-01-01T00:00:00Z") Instant createdAt,

        @Schema(description = "Версия для ETag / If-Match при частичном обновлении", example = "0") Integer version) {
}

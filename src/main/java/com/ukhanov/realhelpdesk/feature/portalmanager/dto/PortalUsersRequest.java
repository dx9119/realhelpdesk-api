package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import java.util.Set;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PortalUsersRequest(
        @NotNull(message = "Список пользователей обязателен")
         @Size(max = 100, message = "Нельзя назначить более 100 пользователей за один запрос") Set<Long> userIds) {
}

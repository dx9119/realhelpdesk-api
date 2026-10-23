package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

public record PortalTransferConfirmRequest(
        @NotBlank(message = "Пароль обязателен")
         @Size(min = 8, message = "Пароль должен быть не менее 8 символов") String password,
        @NotBlank(message = "Причина обязательна")
         @Size(max = 2000, message = "Причина не может превышать 2000 символов")
         @NoHtml String reason) {
}

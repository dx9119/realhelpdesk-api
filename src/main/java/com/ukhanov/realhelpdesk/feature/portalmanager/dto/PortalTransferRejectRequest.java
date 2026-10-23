package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

public record PortalTransferRejectRequest(@NotBlank(message = "Причина обязательна")
                                           @Size(max = 2000, message = "Причина не может превышать 2000 символов")
                                           @NoHtml String reason) {
}

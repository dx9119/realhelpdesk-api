package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

/**
 * Частичное обновление портала: меняются только переданные поля ({@code null} — «не трогать»). Для полной замены ресурса — {@code PUT} с
 * {@code UpdatePortalInfoRequest}.
 */
public record PatchPortalInfoRequest(@Size(max = 255, message = "Название портала не может превышать 255 символов") @NoHtml String name,
        @Size(max = 2500, message = "Описание не может превышать 2500 символов") @NoHtml String description) {

    /** Служебный признак «тело пустое» для сервиса; в JSON/спеку не попадает — клиенту поле не нужно. */
    @JsonIgnore
    public boolean isEmpty() {
        return name == null && description == null;
    }
}

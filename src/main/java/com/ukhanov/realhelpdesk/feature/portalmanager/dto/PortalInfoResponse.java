package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Портал с версией для If-Match; отдаётся с заголовком ETag")
public record PortalInfoResponse(@Schema(description = "Идентификатор портала", example = "1") Long id,

        @Schema(description = "Название портала", example = "Поддержка") String namePortal,

        @Schema(description = "Описание портала", example = "none") String description,

        @Schema(description = "Версия для If-Match; при устаревшей версии — 412", example = "0") Integer version) {

    public PortalInfoResponse(Long id, String namePortal) {
        this(id, namePortal, "none", null);
    }

    public PortalInfoResponse(Long id, String namePortal, String description) {
        this(id, namePortal, description, null);
    }
}

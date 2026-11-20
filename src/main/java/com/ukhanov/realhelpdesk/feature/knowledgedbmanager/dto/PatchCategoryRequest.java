package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

public record PatchCategoryRequest(
        @Schema(description = "Новое название категории; null — не менять") String name,

        @Schema(description = "Новое описание категории; null — не менять") String description) {

    /** Служебный признак «тело пустое» для сервиса; в JSON/спеку не попадает — клиенту поле не нужно. */
    @JsonIgnore
    public boolean isEmpty() {
        return name == null && description == null;
    }
}

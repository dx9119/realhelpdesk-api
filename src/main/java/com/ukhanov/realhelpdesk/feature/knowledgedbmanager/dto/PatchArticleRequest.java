package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

public record PatchArticleRequest(
        @Schema(description = "Новый вопрос; null — не менять") String question,

        @Schema(description = "Новый ответ; null — не менять") String answer,

        @Schema(description = "Новый идентификатор категории; null — не менять") Long categoryId,

        @Schema(description = "Новый идентификатор портала; null — не менять. "
                + "Перевод статьи в глобальные (portalId → null) выполняется только через PUT") Long portalId) {

    /** Служебный признак «тело пустое» для сервиса; в JSON/спеку не попадает — клиенту поле не нужно. */
    @JsonIgnore
    public boolean isEmpty() {
        return question == null && answer == null && categoryId == null && portalId == null;
    }
}

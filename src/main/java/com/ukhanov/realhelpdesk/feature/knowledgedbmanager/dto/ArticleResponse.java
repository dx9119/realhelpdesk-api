package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Статья базы знаний для всех эндпоинтов")
public record ArticleResponse(Long id, String question, String answer,
        @Schema(description = "categoryId — идентификатор категории: UI фильтрует справочник по нему, "
                + "сравнивать по имени нельзя (переименование)") Long categoryId,
        String categoryName,
        @Schema(description = "portalId = null — глобальная статья, видна во всех порталах") Long portalId,
        Instant createdAt,
        @Schema(description = "version — колонка @Version: конфликт при параллельном изменении "
                + "приходит как 409.") Integer version) {
}

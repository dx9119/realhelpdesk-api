package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Элемент справочника категорий базы знаний")
public record CategoryResponse(Long id, String name,
        @Schema(description = "Пояснение для пользователя UI: о чём статьи этой категории") String description,
        @Schema(description = "version — колонка @Version: конфликт при параллельном изменении "
                + "приходит как 409.") Integer version) {
}

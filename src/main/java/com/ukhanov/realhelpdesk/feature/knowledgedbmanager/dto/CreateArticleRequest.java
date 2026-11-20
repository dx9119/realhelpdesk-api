package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

public record CreateArticleRequest(
        @NotBlank(message = "Вопрос не может быть пустым")
         @Size(max = 500, message = "Вопрос не может превышать 500 символов")
         @NoHtml String question,

        @NotBlank(message = "Ответ не может быть пустым")
         @Size(max = 10000, message = "Ответ не может превышать 10 000 символов")
         @NoHtml String answer,

        @NotNull(message = "Идентификатор категории не может быть null") Long categoryId,

        Long portalId) {
}

package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

public record UpdateCategoryRequest(
        @NotBlank(message = "Название категории не может быть пустым")
         @Size(max = 100, message = "Название категории не может превышать 100 символов")
         @NoHtml String name,

        @Size(max = 2000, message = "Описание не может превышать 2000 символов")
         @NoHtml String description) {
}

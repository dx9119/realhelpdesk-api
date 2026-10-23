package com.ukhanov.realhelpdesk.feature.usermanager.dto;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Частичное обновление профиля: null — не трогать")
public record PatchUserInfoRequest(
        @NoHtml @Size(min = 2, max = 20, message = "Имя не может быть короче двух символов")
         @Schema(description = "Имя; null — не менять", example = "Иван") String firstName,

        @NoHtml @Size(min = 2, max = 20, message = "Фамилия не может быть короче двух символов")
         @Schema(description = "Фамилия; null — не менять", example = "Иванов") String lastName,

        @NoHtml @Size(max = 20)
         @Schema(description = "Отчество; null — не менять", example = "Иванович") String middleName,

        @NoHtml @Schema(description = "Дополнительная информация; null — не менять",
                example = "Менеджер") String additionalInfo) {

    @JsonIgnore
    public boolean isEmpty() {
        return firstName == null && lastName == null && middleName == null && additionalInfo == null;
    }
}

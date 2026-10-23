package com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Поле Email обязательно для заполнения")
         @Email(message = "Некорректный формат Email") String email,
        @NotBlank(message = "Поле Пароль обязательно для заполнения")
         @Size(min = 8, message = "Пароль должен быть не менее 8 символов") String password) {
}

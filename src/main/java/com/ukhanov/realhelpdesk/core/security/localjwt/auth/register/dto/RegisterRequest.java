package com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.security.user.model.UserPlatformSource;

public record RegisterRequest(
        @NotBlank(message = "Поле имени обязательно для заполнения")
         @Size(min = 2, max = 50, message = "Имя должно быть не менее 2 символов и не более 50") String firstName,
        @NotBlank(message = "Поле фамилии обязательно для заполнения")
         @Size(min = 2, max = 50, message = "Фамилия должна быть не менее 2 символов и не более 50") String lastName,
        @Size(max = 4_000, message = "Captcha-токен не длиннее 4000 символов") String captchaToken,
        @Size(max = 100, message = "Поле не длиннее 100 символов") String website,
        @NotBlank(message = "Поле Email обязательно для заполнения")
         @Email(message = "Некорректный формат Email") String email,
        @NotBlank(message = "Поле Пароль обязательно для заполнения")
         @Size(min = 8, message = "Пароль должен быть не менее 8 символов") String password,
        Long externalId, UserPlatformSource userPlatformSource) {
}

package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChallengeRequest(
        @NotBlank(message = "action обязателен")
         @Size(max = 50, message = "action не длиннее 50 символов") String action) {
}

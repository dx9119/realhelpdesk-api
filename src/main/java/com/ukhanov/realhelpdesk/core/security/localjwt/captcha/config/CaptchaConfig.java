package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Капча работает в обоих профилях: в keycloak-режиме остаются публичные write-эндпоинты
// (например, выдача подтверждения на почту), защищённые тем же captcha-токеном.
@Configuration
public class CaptchaConfig {

    /** Минимум 32 байта секрета — ключ HMAC-SHA256 не короче. */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * Ключ подписи challenge и токенов капчи. Падает на старте с понятной ошибкой, если секрет не задан или короче 32
     * байт — как {@code JwtConfig} для JWT-секрета.
     */
    @Bean
    public SecretKey captchaSecretKey(CaptchaProperties captchaProperties) {
        String secret = captchaProperties.getHmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("captcha.hmac-secret не содержит значение");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "captcha.hmac-secret: минимум " + MIN_SECRET_BYTES + " байта для HMAC-SHA256, получено "
                            + bytes.length);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}

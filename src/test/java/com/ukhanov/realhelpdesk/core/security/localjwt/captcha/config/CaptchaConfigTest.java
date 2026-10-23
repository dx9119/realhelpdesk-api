package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Сборка капчи: ключ HMAC-SHA256 падает на старте, если секрет пустой или короткий")
class CaptchaConfigTest {

    private final CaptchaConfig config = new CaptchaConfig();

    @Test
    @DisplayName("Секрет 32+ байт — ключ собирается, алгоритм HmacSHA256, длина как у секрета")
    void captchaSecretKey_validSecret_returnsHmacSha256Key() {
        // given
        String secret = "secure-captcha-hmac-secret-0123456789abcdef";

        // when
        SecretKey key = config.captchaSecretKey(properties(secret));

        // then
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
        assertThat(key.getEncoded().length).isGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("Пустой или пробельный секрет — IllegalStateException")
    void captchaSecretKey_blank_fails() {
        // given / when / then
        assertThatThrownBy(() -> config.captchaSecretKey(properties("   "))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captcha.hmac-secret не содержит значение");
        assertThatThrownBy(() -> config.captchaSecretKey(properties(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captcha.hmac-secret не содержит значение");
    }

    @Test
    @DisplayName("Секрет короче 32 байт — IllegalStateException с длиной")
    void captchaSecretKey_tooShort_fails() {
        // given / when / then
        assertThatThrownBy(() -> config.captchaSecretKey(properties("0123456789abcdef0123456789abcde")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("минимум 32 байта");
    }

    private CaptchaProperties properties(String secret) {
        CaptchaProperties properties = new CaptchaProperties();
        properties.setEnabled(true);
        properties.setHmacSecret(secret);
        properties.setPowDifficulty(18);
        properties.setChallengeTtlSeconds(300);
        properties.setTokenTtlSeconds(300);
        properties.setMinFillSeconds(0);
        return properties;
    }
}

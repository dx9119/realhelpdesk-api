package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Конфигурация JWT: JwtConfig")
class JwtConfigTest {

    /** 32 байта в Base64 — минимум для подписи HS256. */
    private static final String VALID_SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("Из валидных настроек собирается конфиг с ключом и адресами")
    void constructor_validProperties_returnsCompleteConfig() {

        // given
        // when
        JwtConfig config = new JwtConfig(validProperties());

        // then
        assertThat(config.getJwtKey().getEncoded()).hasSize(32);
        assertThat(config.getIssuer()).isEqualTo("https://example.com");
        assertThat(config.getAudience()).containsExactly("https://example.com");
        assertThat(config.getAccessTokenExpirationSeconds()).isEqualTo(3600);
        assertThat(config.getRefreshTokenExpirationSeconds()).isEqualTo(2592000);
    }

    @Test
    @DisplayName("Пустой секрет — ошибка на старте с именем ключа")
    void constructor_blankSecret_throwsIllegalArgument() {

        // given
        // when
        JwtProperties properties = validProperties();
        properties.setSecretForGenJwt(" ");

        // then
        assertThatThrownBy(() -> new JwtConfig(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret-for-gen-jwt");
    }

    @Test
    @DisplayName("Секрет не в Base64 — ошибка с подсказкой про HS256")
    void constructor_secretNotBase64_throwsIllegalState() {

        // given
        // when
        JwtProperties properties = validProperties();
        properties.setSecretForGenJwt("не-base64!");

        // then
        assertThatThrownBy(() -> new JwtConfig(properties)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("Пустой issuer — ошибка на старте")
    void constructor_blankIssuer_throwsIllegalArgument() {

        // given
        // when
        JwtProperties properties = validProperties();
        properties.setIssuer("");

        // then
        assertThatThrownBy(() -> new JwtConfig(properties)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jwt.issuer");
    }

    @Test
    @DisplayName("Пустая аудитория — ошибка на старте")
    void constructor_emptyAudience_throwsIllegalArgument() {

        // given
        // when
        JwtProperties properties = validProperties();
        properties.setAudience(Set.of());

        // then
        assertThatThrownBy(() -> new JwtConfig(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.audience");
    }

    @Test
    @DisplayName("Не положительный срок жизни — ошибка на старте")
    void constructor_nonPositiveExpiration_throwsIllegalArgument() {

        // given
        // when
        JwtProperties noAccess = validProperties();
        noAccess.setAccessTokenExpirationSeconds(0);
        // then
        assertThatThrownBy(() -> new JwtConfig(noAccess)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.access-token-expiration-seconds");

        JwtProperties noRefresh = validProperties();
        noRefresh.setRefreshTokenExpirationSeconds(-1);
        assertThatThrownBy(() -> new JwtConfig(noRefresh)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.refresh-token-expiration-seconds");
    }

    @Test
    @DisplayName("Настройки без обязательных значений — ошибка, а не тихий null")
    void constructor_missingProperties_throwsIllegalArgument() {

        // given / when / then
        assertThatThrownBy(() -> new JwtConfig(new JwtProperties())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret-for-gen-jwt");
    }

    private JwtProperties validProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretForGenJwt(VALID_SECRET);
        properties.setIssuer("https://example.com");
        properties.setAudience(Set.of("https://example.com"));
        properties.setAccessTokenExpirationSeconds(3600);
        properties.setRefreshTokenExpirationSeconds(2592000);
        return properties;
    }
}

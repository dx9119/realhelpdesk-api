package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Конфигурация JWT: JwtProperties")
class JwtPropertiesTest {

    @Test
    @DisplayName("Все ключи jwt.* биндятся: секрет, адреса, сроки и cookie")
    void bind_allJwtKeys_populatesProperties() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.secret-for-gen-jwt", "c2VjcmV0");
        environment.setProperty("jwt.issuer", "https://example.com");
        environment.setProperty("jwt.audience", "https://example.com,https://back.example.com");
        environment.setProperty("jwt.access-token-expiration-seconds", "3600");
        environment.setProperty("jwt.refresh-token-expiration-seconds", "2592000");
        environment.setProperty("jwt.cookie.same-site", "Lax");
        environment.setProperty("jwt.cookie.domain", ".example.com");

        JwtProperties properties = bind(environment);

        // then
        assertThat(properties.getSecretForGenJwt()).isEqualTo("c2VjcmV0");
        assertThat(properties.getIssuer()).isEqualTo("https://example.com");
        assertThat(properties.getAudience()).containsExactlyInAnyOrder("https://example.com", "https://back.example.com");
        assertThat(properties.getAccessTokenExpirationSeconds()).isEqualTo(3600);
        assertThat(properties.getRefreshTokenExpirationSeconds()).isEqualTo(2592000);
        assertThat(properties.getCookie().getSameSite()).isEqualTo(SameSite.LAX);
        assertThat(properties.getCookie().getDomain()).isEqualTo(".example.com");
    }

    @Test
    @DisplayName("jwt.cookie.same-site: регистр не важен — строка превращается в элемент enum SameSite")
    void bind_mixedCaseSameSite_mapsToStrictEnum() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.cookie.same-site", "sTrIcT");

        JwtProperties properties = bind(environment);

        // then
        assertThat(properties.getCookie().getSameSite()).isEqualTo(SameSite.STRICT);
    }

    @Test
    @DisplayName("Неизвестное значение same-site — биндинг падает, а не уходит в cookie строкой")
    void bind_unknownSameSite_throwsBindException() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.cookie.same-site", "Sometimes");

        // then
        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class).hasMessageContaining("jwt.cookie.same-site");
    }

    @Test
    @DisplayName("jwt.cookie.same-site по умолчанию None — кросс-доменный фронт")
    void bind_withoutSameSite_defaultsToNone() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.issuer", "https://example.com");

        JwtProperties properties = bind(environment);

        // then
        assertThat(properties.getCookie().getSameSite()).isEqualTo(SameSite.NONE);
    }

    @Test
    @DisplayName("jwt.cookie.domain не задан — null: cookie остаётся host-only, как до появления ключа")
    void bind_withoutCookieDomain_defaultsToNull() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.issuer", "https://example.com");

        JwtProperties properties = bind(environment);

        // then
        assertThat(properties.getCookie().getDomain()).isNull();
    }

    @Test
    @DisplayName("jwt.cookie.domain пустой — null, а не пустая строка в атрибуте Set-Cookie")
    void cookieDomain_blankValue_normalizesToNull() {

        // given
        // when
        JwtProperties properties = new JwtProperties();

        properties.getCookie().setDomain("   ");

        // then
        assertThat(properties.getCookie().getDomain()).isNull();
    }

    @Test
    @DisplayName("Без jwt.* биндинг не срабатывает")
    void bind_emptyEnvironment_isNotBound() {

        // given
        // when
        BindResult<JwtProperties> result = Binder.get(new MockEnvironment()).bind("jwt", Bindable.of(JwtProperties.class));

        // then
        assertThat(result.isBound()).isFalse();
    }

    private JwtProperties bind(MockEnvironment environment) {
        return Binder.get(environment).bind("jwt", Bindable.of(JwtProperties.class)).get();
    }
}

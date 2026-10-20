package com.ukhanov.realhelpdesk.core.security.ratelimit.config;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    @DisplayName("require по известному ключу возвращает настроенный лимит")
    void require_knownKey_returnsConfiguredLimit() {

        // given
        // when
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLimits(Map.of("captcha", new RateLimitProperties.Limit(30, 60)));

        RateLimitProperties.Limit limit = properties.require("captcha");

        // then
        assertThat(limit.requests()).isEqualTo(30);
        assertThat(limit.windowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("require по отсутствующему ключу бросает IllegalStateException")
    void require_unknownKey_throwsIllegalStateException() {

        // given
        // when
        RateLimitProperties properties = new RateLimitProperties();

        // then
        assertThatThrownBy(() -> properties.require("auth-login")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ratelimit.limits.auth-login");
    }

    @Test
    @DisplayName("Limit с неположительными значениями отклоняется")
    void limit_nonPositiveValues_throwsIllegalArgumentException() {

        // given / when / then
        assertThatThrownBy(() -> new RateLimitProperties.Limit(0, 60)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests");
        assertThatThrownBy(() -> new RateLimitProperties.Limit(10, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
    }

    @Test
    @DisplayName("Лимиты биндятся из конфигурации ratelimit.limits.*")
    void bind_limitsFromProperties_populatesLimits() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ratelimit.limits.captcha.requests", "30");
        environment.setProperty("ratelimit.limits.captcha.window-seconds", "60");

        RateLimitProperties properties = Binder.get(environment).bind("ratelimit", Bindable.of(RateLimitProperties.class)).get();

        // then
        assertThat(properties.require("captcha")).isEqualTo(new RateLimitProperties.Limit(30, 60));
    }
}

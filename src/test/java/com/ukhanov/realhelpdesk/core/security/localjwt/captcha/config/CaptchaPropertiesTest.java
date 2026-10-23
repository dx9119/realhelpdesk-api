package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Конфигурация капчи: CaptchaProperties")
class CaptchaPropertiesTest {

    @Test
    @DisplayName("captcha.enabled биндится из конфигурации")
    void bind_enabledProperty_mapsToTrue() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("captcha.enabled", "true");

        CaptchaProperties properties = bind(environment);

        // then
        assertThat(properties.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("Ключи риск-движка и эмодзи-эскалации биндятся из конфигурации")
    void bind_riskAndEmojiKeys_bindsConfiguredValues() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("captcha.emoji-enabled", "true");
        environment.setProperty("captcha.risk-failure-threshold", "5");
        environment.setProperty("captcha.risk-failure-window-seconds", "600");

        CaptchaProperties properties = bind(environment);

        // then
        assertThat(properties.getEmojiEnabled()).isTrue();
        assertThat(properties.getRiskFailureThreshold()).isEqualTo(5);
        assertThat(properties.getRiskFailureWindowSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("Без captcha.* биндинг не срабатывает")
    void bind_emptyEnvironment_isNotBound() {

        // given
        // when
        BindResult<CaptchaProperties> result = Binder.get(new MockEnvironment())
                .bind("captcha", Bindable.of(CaptchaProperties.class));

        // then
        assertThat(result.isBound()).isFalse();
    }

    private CaptchaProperties bind(MockEnvironment environment) {
        return Binder.get(environment).bind("captcha", Bindable.of(CaptchaProperties.class)).get();
    }
}

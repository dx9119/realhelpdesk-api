package com.ukhanov.realhelpdesk.core.config;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Клиентский IP: свойства client-ip.*")
class ClientIpPropertiesTest {

    @Test
    @DisplayName("Дефолты: заголовкам не доверяем, приоритет — X-Forwarded-For, берём первый элемент списка")
    void clientIpProperties_newInstance_returnsSecureDefaults() {

        // given
        // when
        ClientIpProperties properties = new ClientIpProperties();

        // then
        assertThat(properties.isTrustProxyHeaders()).isFalse();
        assertThat(properties.getForwardedIndex()).isZero();
        assertThat(properties.getHeaders()).containsExactly("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP",
                "True-Client-IP",
                "X-Client-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP", "Forwarded");
    }

    @Test
    @DisplayName("Биндинг из конфигурации: флаг, свой список заголовков, индекс элемента (в т.ч. отрицательный)")
    void bind_propertiesFromEnvironment_returnsBoundValues() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("client-ip.trust-proxy-headers", "true");
        environment.setProperty("client-ip.headers", "X-Real-IP,CF-Connecting-IP");
        environment.setProperty("client-ip.forwarded-index", "-1");

        ClientIpProperties properties = Binder.get(environment)
                .bind("client-ip", Bindable.of(ClientIpProperties.class))
                .get();

        // then
        assertThat(properties.isTrustProxyHeaders()).isTrue();
        assertThat(properties.getHeaders()).isEqualTo(List.of("X-Real-IP", "CF-Connecting-IP"));
        assertThat(properties.getForwardedIndex()).isEqualTo(-1);
    }
}

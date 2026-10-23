package com.ukhanov.realhelpdesk.core.http;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Клиентский IP: из какого заголовка приложение берёт адрес посетителя")
class ClientIpResolverTest {

    private static final String SOCKET_IP = "10.0.0.5";

    @Test
    @DisplayName("Заголовкам не доверяем — всегда адрес сокета, присланный клиентом заголовок игнорируется")
    void resolve_trustDisabled_returnsSocketIp() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        // then
        assertThat(resolve(trusting(false), request)).isEqualTo(SOCKET_IP);
    }

    @Test
    @DisplayName("Доверяем заголовкам — берём адрес из настроенного заголовка, а не из сокета")
    void resolve_trustEnabled_returnsHeaderIp() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        // then
        assertThat(resolve(trusting(true), request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("Порядок заголовков задаёт приоритет: раньше в списке — важнее")
    void resolve_configuredHeaderOrder_determinesWinner() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Real-IP", "198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        ClientIpResolver xffFirst = resolver(true, List.of("X-Forwarded-For", "X-Real-IP"), 0);
        ClientIpResolver realIpFirst = resolver(true, List.of("X-Real-IP", "X-Forwarded-For"), 0);

        // then
        assertThat(resolve(xffFirst, request)).isEqualTo("203.0.113.9");
        assertThat(resolve(realIpFirst, request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("Из списка X-Forwarded-For берётся элемент с индексом; отрицательный считается справа")
    void resolve_forwardedIndex_selectsEntry() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 198.51.100.7, 10.0.0.1");

        // then
        assertThat(resolve(resolver(true, null, 0), request)).isEqualTo("203.0.113.9");
        assertThat(resolve(resolver(true, null, 1), request)).isEqualTo("198.51.100.7");
        assertThat(resolve(resolver(true, null, -1), request)).isEqualTo("10.0.0.1");
        assertThat(resolve(resolver(true, null, 9), request)).isEqualTo(SOCKET_IP);
    }

    @Test
    @DisplayName("Непригодные значения (unknown, пусто) не считаются адресом — проверяется следующий заголовок")
    void resolve_unusableHeaderValues_fallsThroughToNext() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Real-IP", " ");

        ClientIpResolver resolver = resolver(true, List.of("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP"), 0);
        request.addHeader("CF-Connecting-IP", "203.0.113.9");

        // then
        assertThat(resolve(resolver, request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("Ни один заголовок не подошёл — адрес сокета")
    void resolve_noUsableHeaders_returnsSocketIp() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "unknown");

        // then
        assertThat(resolve(trusting(true), request)).isEqualTo(SOCKET_IP);
    }

    @Test
    @DisplayName("Приводим значение к чистому адресу: порт, кавычки и формат Forwarded (RFC 7239)")
    void resolve_messyHeaderValues_normalizesToCleanIp() {

        // given
        // when
        ClientIpResolver resolver = trusting(true);

        // then
        assertThat(resolveWithHeader(resolver, "X-Forwarded-For", "203.0.113.9:8080")).isEqualTo("203.0.113.9");
        assertThat(resolveWithHeader(resolver, "X-Forwarded-For", "\"203.0.113.9\"")).isEqualTo("203.0.113.9");
        assertThat(resolveWithHeader(resolver, "X-Forwarded-For", "2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(resolveWithHeader(resolver, "X-Forwarded-For", "\"[2001:db8::1]:443\"")).isEqualTo("2001:db8::1");
        assertThat(resolveWithHeader(resolver, "Forwarded", "for=203.0.113.9;proto=https;by=10.0.0.1"))
                .isEqualTo("203.0.113.9");
        assertThat(resolveWithHeader(resolver, "Forwarded", "for=\"[2001:db8::1]:443\", for=10.0.0.1"))
                .isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("Пустые имена заголовков в конфигурации пропускаются, а не ломают определение адреса")
    void resolve_blankHeaderNames_areSkipped() {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Real-IP", "203.0.113.9");

        // then
        assertThat(resolve(resolver(true, List.of("", " ", "X-Real-IP"), 0), request)).isEqualTo("203.0.113.9");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tickets");
        request.setRemoteAddr(SOCKET_IP);
        return request;
    }

    private static ClientIpResolver trusting(boolean trust) {
        return resolver(trust, null, 0);
    }

    private static ClientIpResolver resolver(boolean trust, List<String> headers, int forwardedIndex) {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustProxyHeaders(trust);
        properties.setForwardedIndex(forwardedIndex);
        if (headers != null) {
            properties.setHeaders(headers);
        }
        return new ClientIpResolver(properties);
    }

    private static String resolve(ClientIpResolver resolver, MockHttpServletRequest request) {
        return resolver.resolve(request);
    }

    private static String resolveWithHeader(ClientIpResolver resolver, String header, String value) {
        MockHttpServletRequest request = request();
        request.addHeader(header, value);
        return resolve(resolver, request);
    }
}

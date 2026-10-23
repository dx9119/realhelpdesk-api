package com.ukhanov.realhelpdesk.core.filter;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;
import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Логирование запросов: IP клиента в MDC clientIp")
class LoggingFilterTest {

    @Test
    @DisplayName("Заголовкам доверяем — в MDC адрес из заголовка прокси, после фильтра MDC очищен")
    void doFilter_trustedProxyHeader_usesHeaderIpAndClearsMdc() throws Exception {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        AtomicReference<String> mdcIp = new AtomicReference<>();

        filter(true).doFilter(request, new MockHttpServletResponse(), chain(mdcIp));

        // then
        assertThat(mdcIp.get()).isEqualTo("203.0.113.9");
        assertThat(MDC.get(LoggingFilter.CLIENT_IP)).isNull();
    }

    @Test
    @DisplayName("Заголовкам не доверяем — в MDC адрес сокета, подделанный клиентом заголовок не берётся")
    void doFilter_untrustedProxyHeaders_usesSocketAddress() throws Exception {

        // given
        // when
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        AtomicReference<String> mdcIp = new AtomicReference<>();

        filter(false).doFilter(request, new MockHttpServletResponse(), chain(mdcIp));

        // then
        assertThat(mdcIp.get()).isEqualTo("10.0.0.5");
    }

    private static LoggingFilter filter(boolean trustProxyHeaders) {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustProxyHeaders(trustProxyHeaders);
        return new LoggingFilter(new ClientIpResolver(properties));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tickets");
        request.setRemoteAddr("10.0.0.5");
        return request;
    }

    @Test
    @DisplayName("В MDC role=ANONYMOUS до аутентификации, после фильтра MDC очищен")
    void doFilter_unauthenticated_setsAnonymousRoleAndClearsMdc() throws Exception {

        // given
        // when
        AtomicReference<String> role = new AtomicReference<>();

        filter(false).doFilter(request(), new MockHttpServletResponse(),
                (req, res) -> role.set(MDC.get(LoggingFilter.ROLE)));

        // then
        assertThat(role.get()).isEqualTo("ANONYMOUS");
        assertThat(MDC.get(LoggingFilter.ROLE)).isNull();
    }

    /** Запоминаем значение MDC ровно в момент обработки запроса — после фильтра он MDC чистит. */
    private static FilterChain chain(AtomicReference<String> mdcIp) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                mdcIp.set(MDC.get(LoggingFilter.CLIENT_IP));
            }
        };
    }
}

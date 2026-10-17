package com.ukhanov.realhelpdesk.core.http;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Единственное место, где приложение определяет IP посетителя: логи ({@code LoggingFilter}, MDC {@code clientIp}) и ключ рейт-лимита
 * ({@code RateLimitInterceptor}) пользуются одним и тем же резолвером, чтобы лог и лимит видели один и тот же адрес.
 *
 * <p>
 * Порядок поиска задаёт {@link ClientIpProperties}: без доверия заголовкам — сокетный адрес; с довериями — первый пригодный из списка
 * {@link ClientIpProperties#getHeaders()}, элемент списка выбирается по {@link ClientIpProperties#getForwardedIndex()}.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    /** Значение-заглушка, которым прокси обозначают «адреса нет»; брать его как IP нельзя. */
    private static final String UNKNOWN = "unknown";

    private final ClientIpProperties properties;

    /**
     * IP клиента для логов и рейт-лимита. Заголовкам доверяем только при {@code client-ip.trust-proxy-headers=true}; иначе — адрес сокета
     * запроса.
     *
     * @return адрес клиента; {@code null} возможен только если контейнер вернул {@code null} из {@code getRemoteAddr()}
     */
    public String resolve(HttpServletRequest request) {
        if (properties.isTrustProxyHeaders()) {
            for (String header : properties.getHeaders()) {
                if (header == null || header.isBlank()) {
                    continue;
                }
                String ip = extract(request.getHeader(header.trim()));
                if (ip != null) {
                    // Источник виден в dev: без него не объяснить, почему IP в логе не совпал с ожидаемым
                    logger.debug("IP клиента: источник={}, trustProxyHeaders=true", header.trim());
                    return ip;
                }
            }
        }
        // Заголовкам не доверяем (или ни один не дал адреса) — берём адрес сокета
        logger.debug("IP клиента: источник=socket, trustProxyHeaders={}", properties.isTrustProxyHeaders());
        return request.getRemoteAddr();
    }

    /** Достаёт адрес из значения одного заголовка; {@code null} — адреса нет, пробуем следующий заголовок. */
    private String extract(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String[] entries = headerValue.split(",");
        int index = properties.getForwardedIndex();
        int position = index < 0 ? entries.length + index : index;
        return position >= 0 && position < entries.length ? normalize(entries[position]) : null;
    }

    /**
     * Приводит элемент списка к чистому адресу: {@code Forwarded: for=1.2.3.4;proto=http}, порты ({@code 1.2.3.4:8080},
     * {@code [2001:db8::1]:443}), кавычки и {@code unknown} отбрасываются.
     */
    private String normalize(String entry) {
        String value = entry.trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        int equalsIndex = value.indexOf('=');
        if (equalsIndex >= 0) {
            if (!value.regionMatches(true, 0, "for=", 0, 4)) {
                return null;
            }
            value = value.substring(equalsIndex + 1).trim();
        }
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty() || UNKNOWN.equalsIgnoreCase(value) || value.startsWith("_")) {
            return null;
        }
        int firstColon = value.indexOf(':');
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            value = close > 0 ? value.substring(1, close) : value.substring(1);
        } else if (firstColon > 0 && value.indexOf(':', firstColon + 1) < 0) {
            // один двоеточие в не-IPv6 значении — это порт: 1.2.3.4:8080 → 1.2.3.4
            value = value.substring(0, firstColon);
        }
        return value.isEmpty() ? null : value;
    }
}

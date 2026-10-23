package com.ukhanov.realhelpdesk.core.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Как приложение получает IP посетителя: из заголовков, которые проставляет прокси, или напрямую из сокета запроса.
 *
 * <p>
 * Прокси передают адрес клиента по-разному — у nginx это {@code X-Real-IP}, у балансировщиков и CDN —
 * {@code X-Forwarded-For} или {@code CF-Connecting-IP}, у Traefik — {@code Forwarded} по RFC 7239. Список
 * {@link #getHeaders()} задаёт, в каком порядке их проверять; результат применяется одинаково везде, где нужен адрес
 * клиента (логи и ключ рейт-лимита).
 * </p>
 *
 * <p>
 * Заголовкам нельзя доверять вне прокси: клиент может прислать свой и подставить чужой IP в логи и в счётчик лимита,
 * поэтому всё управляется одним флагом {@link #isTrustProxyHeaders()} (переменная окружения
 * {@code TRUST_PROXY_HEADERS}).
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "client-ip")
@Getter
@Setter
public class ClientIpProperties {

    /**
     * Брать адрес клиента из заголовков прокси; {@code false} — всегда {@code request.getRemoteAddr()}. Только за
     * прокси!
     */
    private boolean trustProxyHeaders = false;

    /**
     * Заголовки в порядке приоритета: берётся первый, из которого удалось извлечь адрес. Значение может содержать
     * список через запятую (в {@code X-Forwarded-For} их несколько) — какой элемент брать, задаёт
     * {@link #getForwardedIndex()}.
     */
    private List<String> headers = new ArrayList<>(
            List.of("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP",
                    "X-Client-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP", "Forwarded"));

    /**
     * Индекс элемента списка в значении заголовка: {@code 0} — первый слева (исходный клиент, адрес присылает сам
     * прокси), {@code 1} — второй и так далее; отрицательное считается справа ({@code -1} — ближайший приложению
     * прокси). Если за приложением один прокси, оставляйте {@code 0}; если цепочка из нескольких — считайте от правого
     * края, чтобы подставить себя не смог клиент.
     */
    private int forwardedIndex = 0;
}

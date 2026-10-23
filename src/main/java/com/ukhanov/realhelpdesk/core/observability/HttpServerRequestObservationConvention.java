package com.ukhanov.realhelpdesk.core.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * Добавляет к http.server.requests низкокардинальный тег {@code role} — роль аутентифицированного пользователя
 * (OPERATOR/USER/NONE) либо ANONYMOUS.
 *
 * <p>
 * Роль читается из атрибута запроса, а не из {@code SecurityContextHolder}: наблюдение останавливается в
 * {@code ServerHttpObservationFilter}, то есть после завершения security-цепочки, когда контекст безопасности уже
 * очищен. Допустимые значения тега — конечный набор имён ролей, никаких id, email и других ПДн.
 * </p>
 */
public class HttpServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    /** Атрибут запроса с ролью: его выставляет JwtAuthFilter сразу после аутентификации. */
    public static final String ROLE_ATTRIBUTE = HttpServerRequestObservationConvention.class.getName() + ".role";

    /** Имя тега роли в метрике и в спане. */
    public static final String ROLE_TAG = "role";

    /** Префикс ролей Spring Security: authority приложения выглядит как {@code ROLE_OPERATOR}. */
    public static final String ROLE_PREFIX = "ROLE_";

    private static final String ANONYMOUS = "ANONYMOUS";

    /**
     * Ресурсный обработчик Spring матчит {@code /**} на несуществующие пути: шаблон не описывает маршрут, поэтому такие
     * запросы помечаются NOT_FOUND — как и случаи без шаблона в {@link DefaultServerRequestObservationConvention}.
     * Иначе каждый неизвестный URL прятался бы под одной строкой, но с неверным смыслом, а кардинальность держалась бы
     * только случайно.
     */
    private static final String RESOURCE_FALLBACK_PATTERN = "/**";

    private static final String NOT_FOUND = "NOT_FOUND";

    public HttpServerRequestObservationConvention(String name) {
        super(name);
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        List<KeyValue> keyValues = new ArrayList<>();
        super.getLowCardinalityKeyValues(context).forEach(keyValues::add);
        keyValues.add(KeyValue.of(ROLE_TAG, role(context)));
        // Micrometer рассчитывает на отсортированные по имени теги: дешевле сравнивать серии.
        keyValues.sort(Comparator.comparing(KeyValue::getKey));
        return KeyValues.of(keyValues);
    }

    @Override
    protected KeyValue uri(ServerRequestObservationContext context) {
        if (RESOURCE_FALLBACK_PATTERN.equals(context.getPathPattern())) {
            return KeyValue.of(ServerHttpObservationDocumentation.LowCardinalityKeyNames.URI, NOT_FOUND);
        }
        return super.uri(context);
    }

    private String role(ServerRequestObservationContext context) {
        if (context.getCarrier() instanceof HttpServletRequest request) {
            Object role = request.getAttribute(ROLE_ATTRIBUTE);
            if (role instanceof String value && !value.isBlank()) {
                return roleName(value);
            }
        }
        return ANONYMOUS;
    }

    /**
     * Имя роли для тега метрики и MDC: без префикса Spring Security {@code ROLE_} — тот же набор OPERATOR/USER/NONE.
     */
    public static String roleName(String role) {
        return role.startsWith(ROLE_PREFIX) ? role.substring(ROLE_PREFIX.length()) : role;
    }
}

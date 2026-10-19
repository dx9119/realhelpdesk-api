package com.ukhanov.realhelpdesk.core.config;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

import com.ukhanov.realhelpdesk.core.observability.HttpServerRequestObservationConvention;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

/**
 * Защитный слой наблюдаемости: отсекает шум и добавляет тег роли в http.server.requests, ограничивает кардинальность тегов и отключает
 * неиспользуемые метрики.
 *
 * <p>
 * В Boot 4 свойства {@code management.observations.http.server.requests.ignore-patterns} больше нет, поэтому исключения задаются
 * {@link ObservationPredicate}: бин подхватывается {@code ObservationRegistryPostProcessor} и гасит наблюдение целиком — ни метрики, ни
 * спана. То же относится и к управляющему порту: пробы health и скрейп метрик не должны раздувать список uri. Поскольку заглушенное
 * HTTP-наблюдение оставляло security-участки цепочки корнями (Tempo получал по два осколка-трейса на скрейп), тот же предикат подавляет
 * security-наблюдения без родителя — детали в {@link #ignoreNoiseObservationPredicate()}.
 * </p>
 *
 * <p>
 * MeterFilter'ы — вторая линия защиты после свойств {@code management.metrics.*}: потолок уникальных значений uri, срез опасных тегов на
 * случай их появления в будущем коде и deny-список метрик, согласованный по замеру /actuator/prometheus (см. docs/observability/metrics.md,
 * раздел «Отключённые метрики»).
 * </p>
 */
@Configuration
public class ObservabilityConfiguration {

    private static final String SERVER_REQUESTS = "http.server.requests";

    private static final String CLIENT_REQUESTS = "http.client.requests";

    /** Потолок уникальных значений тега uri (дублирует management.metrics.web.server.max-uri-tags для server-цепочки). */
    private static final int MAX_URI_TAGS = 100;

    /**
     * Теги, которых не должно быть ни в одной метрике: идентификаторы и ПДн взрывают кардинальность и утекают в базу метрик. Срезаются на
     * уровне registry, даже если новый код случайно добавит их в tag list.
     */
    private static final Set<String> DANGEROUS_TAGS = Set.of("userId", "user_id", "email", "ticketId", "ticket_id", "sessionId", "token",
            "password");

    /**
     * Метрики, отключённые по согласованию: диск контейнера отражает хостовую ФС и вводит в заблуждение, буферы JVM и внутренние счётчики
     * Hibernate (flush/sessions/optimistic) не нужны для алертинга. Остальные семейства отключены свойствами management.metrics.enable.* в
     * application.properties.
     */
    private static final Set<String> DENIED_METRICS = Set.of("disk.free", "disk.total", "jvm.buffer.count", "jvm.buffer.memory.used",
            "jvm.buffer.total.capacity", "hibernate.flushes", "hibernate.sessions.open", "hibernate.sessions.closed",
            "hibernate.optimistic.failures");

    /**
     * Пути, запросы к которым не несут информации о поведении приложения: actuator (включая пробы и скрейп), Swagger UI и контракты
     * OpenAPI.
     */
    private static final String[] NOISE_PREFIXES = {"/actuator", "/swagger-ui", "/v3/api-docs"};

    /**
     * Префикс наблюдений Spring Security: наблюдения цепочки фильтров и авторизации — по смыслу дети HTTP-наблюдения запроса. Как корни они
     * всегда осколки: без обрамляющего span каждый участок security-цепочки создаёт свой трейс.
     */
    private static final String SECURITY_OBSERVATION_PREFIX = "spring.security.";

    @Bean
    ServerRequestObservationConvention serverRequestObservationConvention(
            @Value("${management.observations.http.server.requests.name:http.server.requests}") String observationName) {
        return new HttpServerRequestObservationConvention(observationName);
    }

    /**
     * Два правила одного предиката:
     *
     * <ol>
     * <li>Шумные uri (см. {@link #NOISE_PREFIXES}) не порождают {@code http.server.requests} — ни метрики, ни спана.</li>
     * <li>Security-наблюдения без родителя подавляются: если HTTP-наблюдение запроса заглушено (шумный uri) или его нет, security-участки
     * стали бы отдельными корневыми трейсами — «security filterchain before/after» счислялись в Tempo осколками, по два на каждый скрейп.
     * Micrometer ставит parent в контекст до вызова предиката, поэтому проверка надёжна: под активным HTTP-наблюдением security-спаны
     * сохраняются и вкладываются как раньше.</li>
     * </ol>
     */
    @Bean
    ObservationPredicate ignoreNoiseObservationPredicate() {
        return (name, context) -> {
            if (SERVER_REQUESTS.equals(name)) {
                return !isNoise(context);
            }
            return !name.startsWith(SECURITY_OBSERVATION_PREFIX) || context.getParentObservation() != null;
        };
    }

    /**
     * Потолок uri-тегов: после MAX_URI_TAGS уникальных значений новые uri отклоняются, а не плодят серии. Для http.server.requests тот же
     * лимит дублируется свойством management.metrics.web.server.max-uri-tags. В текущей версии Micrometer политика превышения задаётся
     * фильтром-аргументом — передаём deny (молчаливый; Boot-версия свойства логирует warn один раз).
     */
    @Bean
    MeterFilter uriCardinalityMeterFilter() {
        MeterFilter serverUriCap = MeterFilter.maximumAllowableTags(SERVER_REQUESTS, "uri", MAX_URI_TAGS, MeterFilter.deny());
        MeterFilter clientUriCap = MeterFilter.maximumAllowableTags(CLIENT_REQUESTS, "uri", MAX_URI_TAGS, MeterFilter.deny());
        // В текущей версии Micrometer у MeterFilter нет and()/compose — объединяем вручную.
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                MeterFilterReply reply = serverUriCap.accept(id);
                return reply != MeterFilterReply.NEUTRAL ? reply : clientUriCap.accept(id);
            }
        };
    }

    @Bean
    MeterFilter ignoreDangerousTagsMeterFilter() {
        return MeterFilter.ignoreTags(DANGEROUS_TAGS.toArray(String[]::new));
    }

    @Bean
    MeterFilter denyUnusedMetricsMeterFilter() {
        return MeterFilter.deny(meterId -> DENIED_METRICS.contains(meterId.getName()));
    }

    private static boolean isNoise(Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext serverContext)) {
            return false;
        }
        HttpServletRequest request = serverContext.getCarrier();
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        for (String prefix : NOISE_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

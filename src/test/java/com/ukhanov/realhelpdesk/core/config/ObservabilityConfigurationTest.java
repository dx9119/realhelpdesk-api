package com.ukhanov.realhelpdesk.core.config;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

/**
 * Защитный слой наблюдаемости: MeterFilter'ы (deny, ignoreTags, потолок uri) и ObservationPredicate (шумные uri, security-наблюдения без
 * родителя). MeterFilter'ы проверяются на SimpleMeterRegistry без контекста Spring — фильтры применяются в том же порядке, что и бинами в
 * реестре приложения; предикат вызывается напрямую, как его вызывает ObservationRegistry при старте наблюдения (parent уже проставлен в
 * контексте — тот же порядок, что в Observation.createNotStarted).
 */
@DisplayName("Наблюдаемость: MeterFilter'ы (deny, ignoreTags, uri) и предикат шумных/security-наблюдений")
class ObservabilityConfigurationTest {

    private final ObservabilityConfiguration configuration = new ObservabilityConfiguration();

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().meterFilter(configuration.uriCardinalityMeterFilter())
                .meterFilter(configuration.ignoreDangerousTagsMeterFilter()).meterFilter(configuration.denyUnusedMetricsMeterFilter());
    }

    @Test
    @DisplayName("Согласованные deny-метрики (disk, jvm.buffer, внутренние счётчики Hibernate) не регистрируются")
    void denyFilter_blockedMetrics_areNotRegistered() {

        // given
        // when
        meterRegistry.counter("disk.free").increment();
        meterRegistry.counter("disk.total").increment();
        meterRegistry.counter("jvm.buffer.count").increment();
        meterRegistry.counter("jvm.buffer.memory.used").increment();
        meterRegistry.counter("jvm.buffer.total.capacity").increment();
        meterRegistry.counter("hibernate.flushes").increment();
        meterRegistry.counter("hibernate.sessions.open").increment();
        meterRegistry.counter("hibernate.sessions.closed").increment();
        meterRegistry.counter("hibernate.optimistic.failures").increment();

        // then
        assertThat(meterRegistry.find("disk.free").meter()).as("disk.free отклонён").isNull();
        assertThat(meterRegistry.find("jvm.buffer.count").meter()).as("jvm.buffer.count отклонён").isNull();
        assertThat(meterRegistry.find("hibernate.flushes").meter()).as("hibernate.flushes отклонён").isNull();
        // Ключевые метрики из согласованного списка «сохраняем» не задеты
        meterRegistry.counter("hibernate.query.executions").increment();
        meterRegistry.counter("http.server.requests").increment();
        assertThat(meterRegistry.find("hibernate.query.executions").meter()).as("hibernate.query.executions остаётся").isNotNull();
        assertThat(meterRegistry.find("http.server.requests").meter()).as("http.server.requests остаётся").isNotNull();
    }

    @Test
    @DisplayName("Опасные теги (email, userId, ticketId и т.п.) срезаются с любого meter")
    void ignoreTagsFilter_dangerousTags_areStripped() {

        // given
        // when
        meterRegistry.counter("auth.attempts", "type", "login", "email", "user@example.com", "userId", "42", "ticketId", "7").increment();

        Meter meter = meterRegistry.find("auth.attempts").meter();

        // then
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactly("type");
        assertThat(meter.getId().getTags()).extracting(Tag::getValue).containsExactly("login");
    }

    @Test
    @DisplayName("uri-тег: после 100 уникальных значений новые отклоняются, существующие не теряются")
    void uriCardinalityFilter_hundredUniqueUris_capsTag() {

        // given
        // when
        for (int i = 0; i < 100; i++) {
            meterRegistry.counter("http.server.requests", "uri", "/api/v1/generated-" + i).increment();
        }
        meterRegistry.counter("http.server.requests", "uri", "/api/v1/generated-100").increment();

        AtomicInteger uniqueUris = new AtomicInteger();
        meterRegistry.find("http.server.requests").meters().forEach(meter -> {
            meter.getId().getTags().stream().filter(tag -> tag.getKey().equals("uri")).findFirst()
                    .ifPresent(tag -> uniqueUris.incrementAndGet());
        });

        // then
        assertThat(uniqueUris).as("101-й uri отклонён, ровно 100 уникальных значений").hasValue(100);
        assertThat(meterRegistry.find("http.server.requests").tags("uri", "/api/v1/generated-100").meter())
                .as("uri за пределами лимита не зарегистрирован").isNull();
        assertThat(meterRegistry.find("http.server.requests").tags("uri", "/api/v1/generated-99").meter())
                .as("uri в пределах лимита сохраняется").isNotNull();
    }

    @Test
    @DisplayName("Клиентские http-метрики тоже ограничены потолком uri")
    void uriCardinalityFilter_clientRequests_areCapped() {

        // given
        // when
        for (int i = 0; i < 101; i++) {
            meterRegistry.counter("http.client.requests", "uri", "/storage/" + i).increment();
        }

        long clientMeters = meterRegistry.find("http.client.requests").meters().size();

        // then
        assertThat(clientMeters).as("http.client.requests под тем же потолком uri").isEqualTo(100);
    }

    @Test
    @DisplayName("Шумные uri (actuator, swagger) не порождают http.server.requests — ни метрики, ни спана")
    void noisePredicate_noiseUris_suppressServerObservation() {

        // given
        // when
        ObservationPredicate predicate = configuration.ignoreNoiseObservationPredicate();

        // then
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/health"))).as("health-пробы").isFalse();
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/prometheus"))).as("скрейп").isFalse();
        assertThat(predicate.test("http.server.requests", serverContext("/swagger-ui.html"))).as("swagger").isFalse();
        assertThat(predicate.test("http.server.requests", serverContext("/api/v1/tickets"))).as("API-запрос остаётся").isTrue();
        assertThat(predicate.test("http.client.requests", serverContext("/actuator/health"))).as("шум только для server-цепочки").isTrue();
    }

    @Test
    @DisplayName("Security-наблюдения без родителя подавляются: под HTTP-наблюдением — сохраняются")
    void noisePredicate_securityWithoutParent_isSuppressed() {

        // given
        // when
        ObservationPredicate predicate = configuration.ignoreNoiseObservationPredicate();
        Observation.Context orphan = new Observation.Context();

        // then
        assertThat(predicate.test("spring.security.filterchains", orphan)).as("корень-осколок подавлен").isFalse();
        assertThat(predicate.test("spring.security.authorizations", orphan)).isFalse();
        assertThat(predicate.test("spring.security.http.secured.requests", orphan)).isFalse();
        assertThat(predicate.test("spring.security.http.unsecured.requests", orphan)).isFalse();
        assertThat(predicate.test("spring.security.authentications", orphan)).isFalse();
        assertThat(predicate.test("http.server.requests", orphan)).as("не-security корни (сам HTTP, задачи) не трогаем").isTrue();

        ObservationRegistry registry = ObservationRegistry.create();
        Observation http = Observation.start("http.server.requests", registry);
        try {
            Observation.Context child = new Observation.Context();
            child.setParentObservation(http);
            assertThat(predicate.test("spring.security.filterchains", child)).as("security-спаны под HTTP-наблюдением запроса сохраняются")
                    .isTrue();
        } finally {
            http.stop();
        }
    }

    private static Observation.Context serverContext(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return new ServerRequestObservationContext(request, mock(HttpServletResponse.class));
    }
}

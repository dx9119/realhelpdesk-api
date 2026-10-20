package com.ukhanov.realhelpdesk.core.observability;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;

/**
 * Контекст-наблюдаемости: приложение в тестовом профиле стартует с инфраструктурой tracing и prometheus — бины
 * ObservationRegistry/MeterRegistry/Tracer на месте, OTLP-экспорт отключён (тесты не требуют collector'а), а обязательные метрики после
 * обычного запроса видны в реестре.
 *
 * <p>
 * Проверка actuator-безопасности и контракта скрейпа живёт в ActuatorSecurityIT (шаг 3), метрики БД — в DbObservabilityIT; здесь — сводный
 * smoke обязательного набора из плана. Тест делит закэшированный контекст с HttpObservabilityIT/DbObservabilityIT (одинаковый набор
 * properties).
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=test")
@DisplayName("Контекст наблюдаемости: бины, отключённый OTLP-экспорт и обязательные метрики")
class ObservabilityContextIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Бины MeterRegistry, ObservationRegistry и Tracer есть — tracing-инфраструктура подключена")
    void context_observabilityBeans_arePresent() {

        // given
        MeterRegistry meters = meterRegistry;
        ObservationRegistry observations = observationRegistry;
        Tracer tracing = tracer;

        // when / then
        assertThat(meters).as("MeterRegistry — точка записи метрик").isNotNull();
        assertThat(observations).as("ObservationRegistry — точка записи наблюдений (HTTP, сервисные, почта)").isNotNull();
        assertThat(tracing).as("Tracer — мост micrometer-tracing (noop без экспорта, реальный — с management.opentelemetry.enabled=true)")
                .isNotNull();
    }

    @Test
    @DisplayName("OTLP-экспорт в тестовом профиле выключен — внешняя инфраструктура и collector не нужны")
    void context_testProfile_disablesOtlpExport() {

        // given
        // when
        String enabled = environment.getProperty("management.opentelemetry.enabled");

        // then
        assertThat(enabled).as("в тестовом профиле OTel SDK выключен: спаны никуда не экспортируются, старт без collector'а")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("После запроса в реестре есть обязательные метрики: http, пул БД, JVM и бизнес-метрики шага 8")
    void request_healthAndFailedLogin_produceRequiredMetrics() throws IOException, InterruptedException {

        // given
        // when
        int healthStatus = get("/api/v1/health").statusCode();
        HttpResponse<String> login = post("/api/v1/auth/login", "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}");

        // then
        assertThat(healthStatus).isEqualTo(200);

        assertThat(meterRegistry.find("http.server.requests").meter()).as("HTTP-метрики создаются Observation-фильтром Spring MVC")
                .isNotNull();
        assertThat(meterRegistry.find("jvm.memory.used").meter()).as("память JVM — базовый гейдж").isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.active").meter()).as("пул Hikari (тестовый classpath, имя app-main)")
                .isNotNull();
        assertThat(meterRegistry.find("tickets.open").meter()).as("бизнес-gauge открытых заявок регистрируется в конструкторе").isNotNull();

        // Бизнес-метрика аутентификации (шаг 8): фиксируется на неудачном входе, без email в тегах.
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(meterRegistry.find("auth.attempts").tags("type", "login", "result", "failure").meter())
                .as("auth.attempts{type=login,result=failure} растёт на отказе во входе").isNotNull();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).timeout(Duration.ofSeconds(15)).GET().build());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Запрос не выполнен: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос прерван: " + request.uri(), e);
        }
    }
}

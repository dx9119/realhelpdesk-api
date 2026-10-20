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
import org.springframework.boot.test.web.server.LocalManagementPort;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * База данных и миграции: метрики пула Hikari, метрики Hibernate (статистика включается профилем) и готовность БД в readiness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=test")
@DisplayName("База данных: метрики Hikari/Hibernate и readiness")
class DbObservabilityIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("hikaricp.connections.* отдаётся с тегом pool=app-main — каждый пул опознаётся по имени")
    void meterRegistry_hikariPool_metricsUsePoolName() {
        // given / when / then
        assertThat(meterRegistry.find("hikaricp.connections.active").tag("pool", "app-main").meter())
                .as("hikari-пул должен отдавать метрики с настроенным именем").isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.max").tag("pool", "app-main").gauge()).isNotNull();
    }

    @Test
    @DisplayName("hibernate.sessions.open отдаётся — статистика Hibernate подключена (generate_statistics=true в тестовом профиле)")
    void meterRegistry_hibernateStatistics_metricsArePresent() {
        // given / when / then
        assertThat(meterRegistry.find("hibernate.query.executions").meter())
                .as("hibernate-метрики требуют hibernate.generate_statistics=true").isNotNull();
        assertThat(meterRegistry.find("hibernate.statements").meter()).isNotNull();
    }

    @Test
    @DisplayName("readiness отдаёт UP: группа включает readinessState, db, minio (minio выключен в тестовом профиле, DOWN не мешает)")
    void readinessEndpoint_inTestProfile_isUpWithDb() throws IOException, InterruptedException {
        // given
        HttpResponse<String> response = CLIENT
                // when
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}

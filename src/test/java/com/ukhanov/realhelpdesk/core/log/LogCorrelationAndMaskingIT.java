package com.ukhanov.realhelpdesk.core.log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.config.TestAuthenticationConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * В строках лога запроса есть корреляция traceId/spanId из живого OTel SDK, а ПДн (email) в лог не попадает — только
 * маскированный вид. Включённый management.opentelemetry.enabled не создаёт экспортеров: endpoint в тестовом classpath
 * не задан, спаны никуда не уходят.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "management.opentelemetry.enabled=true",
                "management.tracing.sampling.probability=1.0",
                "logging.level.com.ukhanov.realhelpdesk=DEBUG"})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Корреляция traceId/spanId и маскирование ПДн в логах")
class LogCorrelationAndMaskingIT extends AbstractIntegrationTest {

    /** traceId (32 hex) и spanId (16 hex) соседними колонками читаемого паттерна logback-test.xml. */
    private static final String TRACE_AND_SPAN_COLUMNS = "[0-9a-f]{32} \\| [0-9a-f]{16}";

    @LocalServerPort
    private int serverPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("Первый вход нового пользователя: в логе traceId/spanId и маскированный email, полного адреса нет")
    void firstLogin_unknownUser_logsTraceAndMaskedEmail(CapturedOutput output)
            throws IOException, InterruptedException {

        // given
        // Уникальный адрес: KeycloakAuthenticationConverter создаёт учётную запись на первом запросе и логирует
        // маску — на уже существующем пользователе этой строки не будет.
        String email = "correlation-" + UUID.randomUUID() + "@example.com";

        // when
        // Запрос уходит за security-цепочку (токен валиден, пользователь создаётся), но капчи нет — 400 от капчи.
        HttpResponse<String> response = postWithAuth("/api/v1/email/codes", "{}",
                TestAuthenticationConfiguration.tokenFor(email));

        // then
        assertThat(response.statusCode()).as("капча не пройдена — 400").isEqualTo(400);
        String logs = capture(output);
        assertThat(logs).as("корреляция из OTel в строке лога").containsPattern(TRACE_AND_SPAN_COLUMNS);
        assertThat(logs).as("INFO-лог создания пользователя маскирует адрес").contains("c***@example.com");
        assertThat(logs).as("полный email — ПДн, в лог попадать не должен").doesNotContain(email);
    }

    @Test
    @DisplayName("Запрос без токена: в логе traceId/spanId, а тело запроса и заголовок Authorization не логируются")
    void request_withoutToken_logsTraceAndNoPii(CapturedOutput output) throws IOException, InterruptedException {

        // given
        String email = "masked-" + UUID.randomUUID() + "@example.com";
        String bearer = TestAuthenticationConfiguration.tokenFor(email);

        // when
        HttpResponse<String> response = postWithAuth("/api/v1/email/codes",
                "{\"email\":\"" + email + "\"}", "not-a-test-token");

        // then
        assertThat(response.statusCode()).as("невалидный токен — 401 от боевого entry point").isEqualTo(401);
        String logs = capture(output);
        assertThat(logs).as("корреляция из OTel в строке лога").containsPattern(TRACE_AND_SPAN_COLUMNS);
        assertThat(logs).as("полный email — ПДн, в лог попадать не должен").doesNotContain(email);
        assertThat(logs).as("bearer-токен в лог не попадает").doesNotContain(bearer);
    }

    private static String capture(CapturedOutput output) {
        return output.getOut() + output.getErr();
    }

    private HttpResponse<String> postWithAuth(String path, String body, String token)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

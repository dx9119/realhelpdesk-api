package com.ukhanov.realhelpdesk.core.log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * В строках лога запроса есть корреляция traceId/spanId из живого OTel SDK, а ПДн (email, пароль) в лог не попадает —
 * только маскированный вид. Включённый management.opentelemetry.enabled не создаёт экспортеров: endpoint в тестовом
 * classpath не задан, спаны никуда не уходят.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "management.opentelemetry.enabled=true",
                "management.tracing.sampling.probability=1.0",
                "logging.level.com.ukhanov.realhelpdesk=DEBUG"})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Корреляция traceId/spanId и маскирование ПДн в логах")
class LogCorrelationAndMaskingIT extends AbstractIntegrationTest {

    private static final String EMAIL = "john.doe@example.com";

    private static final String PASSWORD = "Sup3rSecret123!";

    /** traceId (32 hex) и spanId (16 hex) соседними колонками читаемого паттерна logback-test.xml. */
    private static final String TRACE_AND_SPAN_COLUMNS = "[0-9a-f]{32} \\| [0-9a-f]{16}";

    @LocalServerPort
    private int serverPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("Запрос восстановления пароля: в логе traceId/spanId и маскированный email, полного адреса нет")
    void passwordReset_unknownUser_logsTraceAndMaskedEmail(CapturedOutput output)
            throws IOException, InterruptedException {

        // given
        // when
        HttpResponse<String> response = post("/api/v1/users/password-resets", "{\"email\":\"" + EMAIL + "\"}");

        // then
        assertThat(response.statusCode()).as("пользователь не найден — 401").isEqualTo(401);
        String logs = capture(output);
        assertThat(logs).as("корреляция из OTel в строке лога").containsPattern(TRACE_AND_SPAN_COLUMNS);
        assertThat(logs).as("debug-лог маскирует адрес").contains("email=j***@example.com");
        assertThat(logs).as("полный email — ПДн, в лог попадать не должен").doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("Отказ во входе: пароль и email из запроса в лог не попадают")
    void login_invalidCredentials_doesNotLogPasswordOrEmail(CapturedOutput output)
            throws IOException, InterruptedException {

        // given
        // when
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}");

        // then
        assertThat(response.statusCode()).as("неверные учётные данные — 401").isEqualTo(401);
        String logs = capture(output);
        assertThat(logs).as("пароль не должен попадать в лог").doesNotContain(PASSWORD);
        assertThat(logs).as("полный email — ПДн, в лог попадать не должен").doesNotContain(EMAIL);
    }

    private static String capture(CapturedOutput output) {
        return output.getOut() + output.getErr();
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .header("Content-Type", "application/json")
                // Изменяющие запросы обязаны нести CSRF-заголовок (CsrfProtectionFilter), иначе 403 до контроллера
                .header("X-Requested-With", "XMLHttpRequest")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

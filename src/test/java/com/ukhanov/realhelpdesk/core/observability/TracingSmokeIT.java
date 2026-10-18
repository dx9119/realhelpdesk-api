package com.ukhanov.realhelpdesk.core.observability;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ukhanov.realhelpdesk.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

/**
 * Интеграционный smoke-тест трейсинга: HTTP-запрос порождает дерево наблюдений с общим корнем — корневой спан http.server.requests и
 * дочерние спаны сервисного слоя (mail.send при регистрации). OTel SDK включён (management.opentelemetry.enabled=true, sampling 1.0): спаны
 * строятся реальным мостом micrometer-tracing, экспорта нет — поэтому тест не требует OTLP collector'а. Регистрация идёт через реальный
 * SMTP (GreenMail — только в интеграционных тестах, failsafe), капча отключена свойством.
 *
 * <p>
 * Список наблюдений собирает ObservationHandler, добавленный в закэшированный контекст один раз на JVM: реестр наблюдений живёт дольше
 * теста, удаления handler'ов Micrometer не поддерживает, поэтому коллектор статический и очищается перед каждым тестом.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.profiles.active=test",
        "management.opentelemetry.enabled=true", "management.tracing.sampling.probability=1.0", "captcha.enabled=false",
        "email.notify=admin@example.com", "email.from=noreply@example.com", "email.domain=example.com"})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Tracing smoke: общий traceId и дочерние спаны сервисного слоя")
class TracingSmokeIT extends PostgresIntegrationTest {

    private static final String SERVER_OBSERVATION = "http.server.requests";

    private static final String SERVICE_OBSERVATION = "mail.send";

    /** traceId (32 hex) и spanId (16 hex) соседними колонками паттерна logback-test.xml. */
    private static final Pattern TRACE_SPAN = Pattern.compile("\\| ([0-9a-f]{32}) \\| ([0-9a-f]{16}) \\|");

    private static final List<Observation.Context> OBSERVED = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean COLLECTOR_REGISTERED = new AtomicBoolean();

    private static final ObservationHandler<Observation.Context> COLLECTOR = new ObservationHandler<>() {
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            OBSERVED.add(context);
        }
    };

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static GreenMail greenMail;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private JavaMailSenderImpl javaMailSender;

    @BeforeAll
    static void startSmtpServer() {
        greenMail = new GreenMail(new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
    }

    @AfterAll
    static void stopSmtpServer() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @BeforeEach
    void setUp() {
        greenMail.reset();
        javaMailSender.setHost("localhost");
        javaMailSender.setPort(greenMail.getSmtp().getPort());
        // GreenMail в тесте принимает анонимную почту; креды тестового профильного Spring Mail снимаем,
        // иначе JavaMailSender уходит в AUTH и сервер отвечает 535.
        javaMailSender.setUsername(null);
        javaMailSender.setPassword(null);
        OBSERVED.clear();
        if (COLLECTOR_REGISTERED.compareAndSet(false, true)) {
            observationRegistry.observationConfig().observationHandler(COLLECTOR);
        }
    }

    @Test
    @DisplayName("Регистрация создаёт корневой спан http.server.requests и дочерние mail.send с общим traceId в логах")
    void registerRequest_createsServerSpanWithServiceLayerChildren(CapturedOutput output) throws IOException, InterruptedException {
        String email = "trace-smoke-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> response = post("/api/v1/auth/register",
                "{\"firstName\":\"Trace\",\"lastName\":\"Smoke\",\"email\":\"" + email + "\",\"password\":\"Sup3rSecret123!\"}");

        assertThat(response.statusCode()).as("регистрация с обходом капчи и живым SMTP").isEqualTo(201);

        Observation.Context root = awaitRootServerObservation();
        assertThat(root.getName()).as("корень дерева — серверное наблюдение").isEqualTo(SERVER_OBSERVATION);

        // Дерево наблюдений: mail.send — дочерние спаны (parent != null), а не отдельные корни.
        List<Observation.Context> mailObservations = OBSERVED.stream().filter(context -> SERVICE_OBSERVATION.equals(context.getName()))
                .toList();
        assertThat(mailObservations).as("регистрация отправляет два письма (админу и пользователю)").hasSizeGreaterThanOrEqualTo(2);
        assertThat(mailObservations)
                .allSatisfy(context -> assertThat(context.getParentObservation()).as("mail.send — дочерний спан, а не корень").isNotNull());

        // Реальный traceId из OTel SDK: строки сервисного слоя и писем — один trace (MailObservation не открывает Scope,
        // поэтому spanId в MDC остаётся серверным — дерево наблюдений проверено выше).
        String logs = output.getOut() + output.getErr();
        String[] registered = findCorrelation(logs, "Зарегистрирован пользователь");
        String[] mailed = findCorrelation(logs, "Письмо отправлено");
        assertThat(registered).as("лог регистрации с колонками traceId/spanId").hasSizeGreaterThan(0);
        assertThat(mailed).as("лог отправки письма с колонками traceId/spanId").hasSizeGreaterThan(0);
        assertThat(registered[0]).as("сервисный слой и письма — один traceId").isEqualTo(mailed[0]);
    }

    /** Ждёт остановки корневого наблюдения: observation стопается на commit ответа — запись может чуть отставать от HTTP-клиента. */
    private Observation.Context awaitRootServerObservation() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            for (Observation.Context context : OBSERVED) {
                if (SERVER_OBSERVATION.equals(context.getName()) && context.getParentObservation() == null) {
                    return context;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("корневое наблюдение http.server.requests не остановилось за 3 с, поймано: "
                + OBSERVED.stream().map(Observation.Context::getName).toList());
    }

    /** Возвращает [traceId, spanId] первой строки лога, содержащей маркер. */
    private static String[] findCorrelation(String logs, String marker) {
        for (String line : logs.split("\\R")) {
            if (!line.contains(marker)) {
                continue;
            }
            Matcher matcher = TRACE_SPAN.matcher(line);
            if (matcher.find()) {
                return new String[]{matcher.group(1), matcher.group(2)};
            }
        }
        return new String[0];
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
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

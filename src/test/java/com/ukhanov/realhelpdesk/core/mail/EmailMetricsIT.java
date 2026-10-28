package com.ukhanov.realhelpdesk.core.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * email.template.sent в prometheus-скрейпе: метрика с тегами type/result доходит до /actuator/prometheus после реальной
 * SMTP-отправки (GreenMail).
 *
 * <p>
 * Unit-проверки метрики — в {@code BusinessMetricsTest} и {@code EmailDeliveryServiceTest} (SimpleMeterRegistry). Здесь
 * важна склейка: Micrometer-реестр приложения → PrometheusMeterRegistry → текст скрейпа, который читает Mimir.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "email.from=noreply@example.com",
        "email.notify=admin@example.com",
        "email.domain=example.com",
        "email.project-name=RealHelpDesk test"})
@DisplayName("email.template.sent: метрика после реальной отправки видна в /actuator/prometheus")
class EmailMetricsIT extends AbstractIntegrationTest {

    private static final String METRICS_USER = "test-metrics";
    private static final String METRICS_PASSWORD = "test-password";

    private static final String RECIPIENT = "user@example.com";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static GreenMail greenMail;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private EmailDeliveryService emailDeliveryService;

    @Autowired
    private JavaMailSenderImpl javaMailSender;

    @Autowired
    private MeterRegistry meterRegistry;

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
    void configureMailSender() {
        greenMail.reset();
        javaMailSender.setHost("localhost");
        javaMailSender.setPort(greenMail.getSmtp().getPort());
        javaMailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // GreenMail без auth: username/password из application-test.properties давали бы 535
        javaMailSender.setUsername(null);
        javaMailSender.setPassword(null);
    }

    @Test
    @DisplayName("После успешной отправки scrape содержит email_template_sent_total{type=...,result=success}")
    void sendEmail_successfulDelivery_visibleInPrometheusScrape() throws Exception {

        // given
        // when
        emailDeliveryService.sendEmail(RECIPIENT, EmailTemplateId.TICKET_CREATED,
                Map.of("ticketId", 42L, "portalId", 5L), NotificationEvent.NEW_TICKET);

        // then
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();

        Counter counter = meterRegistry.find("email.template.sent")
                .tags("type", "TICKET_CREATED", "result", "success")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1);

        String scrape = scrapePrometheus();
        assertThat(scrape)
                .contains("email_template_sent_total")
                .contains("type=\"TICKET_CREATED\"")
                .contains("result=\"success\"");
    }

    @Test
    @DisplayName("Неудача SMTP: result=failure видна в скрейпе, success не появляется")
    void sendEmail_smtpFailure_visibleAsFailureInScrape() {

        // given — порт 1 на localhost никем не слушается: connect refused без остановки GreenMail
        javaMailSender.setPort(1);

        // when
        catchThrowableOfType(MailSendException.class,
                () -> emailDeliveryService.sendEmail(RECIPIENT, EmailTemplateId.TICKET_CREATED,
                        Map.of("ticketId", 42L, "portalId", 5L), NotificationEvent.NEW_TICKET));

        // then
        Counter failure = meterRegistry.find("email.template.sent")
                .tags("type", "TICKET_CREATED", "result", "failure")
                .counter();
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1);

        String scrape = scrapePrometheus();
        assertThat(scrape)
                .contains("email_template_sent_total")
                .contains("type=\"TICKET_CREATED\"")
                .contains("result=\"failure\"");
    }

    private String scrapePrometheus() {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((METRICS_USER + ":" + METRICS_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Скрейп prometheus не выполнен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Скрейп prometheus прерван", e);
        }
    }
}

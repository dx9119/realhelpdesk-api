package com.ukhanov.realhelpdesk.core.security.captcha;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.config.TestAuthenticationConfiguration;
import com.ukhanov.realhelpdesk.core.security.captcha.dto.ChallengeResponse;
import com.ukhanov.realhelpdesk.core.security.captcha.utils.PowHash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной поток PoW-капчи по реальному HTTP: challenge → решение в тесте (тот же PowHash, что на сервере) → solve →
 * бизнес-операция с captcha-токеном. Потребитель капчи в единственном (keycloak) контуре один — выдача кода
 * подтверждения email ({@code POST /api/v1/email/codes}, действие {@code email-code}): он за аутентификацией, поэтому
 * тест предъявляет тестовый bearer-токен. Проверяются и отказы: отсутствующий токен, replay токена, токен чужого
 * действия, неверный nonce. Профиль test: капча включена, difficulty=8 (решается за миллисекунды), minimum-fill=0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "email.notify=admin@example.com", "email.from=noreply@example.com",
                "email.domain=example.com"})
@DisplayName("PoW-капча: полный поток challenge → solve → выдача кода email (HTTP)")
class CaptchaFlowIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Действие потребителя капчи: выдача кода подтверждения email. */
    private static final String ACTION_EMAIL_CODE = "email-code";

    /** Действие без потребителя в единственном контуре: годится как «чужое» для проверки wrong_action. */
    private static final String ACTION_FOREIGN = "register";

    private static GreenMail greenMail;

    @LocalServerPort
    private int serverPort;

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
        javaMailSender.setUsername(null);
        javaMailSender.setPassword(null);
    }

    @Test
    @DisplayName("challenge → верный nonce → solve → выдача кода с токеном: 202")
    void sendCode_validCaptchaToken_returnsAccepted() throws Exception {

        // given
        String token = solve(challenge(ACTION_EMAIL_CODE));

        // when
        HttpResponse<String> response = sendCode(token);

        // then
        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    @DisplayName("Выдача кода без captcha-токена → 400 с code=missing_token")
    void sendCode_noCaptchaToken_returnsMissingToken() throws Exception {

        // given
        // when
        HttpResponse<String> response = sendCode(null);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("missing_token");
    }

    @Test
    @DisplayName("Повторное использование токена → 400 с code=replayed")
    void sendCode_reusedCaptchaToken_returnsReplayed() throws Exception {

        // given
        String token = solve(challenge(ACTION_EMAIL_CODE));
        // when
        assertThat(sendCode(token).statusCode()).isEqualTo(202);

        HttpResponse<String> replay = sendCode(token);

        // then
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(code(replay)).isEqualTo("replayed");
    }

    @Test
    @DisplayName("Токен действия register предъявлен на выдачу кода → 400 с code=wrong_action")
    void sendCode_tokenOfOtherAction_returnsWrongAction() throws Exception {

        // given
        String token = solve(challenge(ACTION_FOREIGN));

        // when
        HttpResponse<String> response = sendCode(token);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("wrong_action");
    }

    @Test
    @DisplayName("solve с нерешённым nonce → 400 с code=pow_failed")
    void solve_wrongNonce_returnsPowFailed() throws Exception {

        // given
        Challenge challenge = challenge(ACTION_EMAIL_CODE);
        String wrongNonce = nonceThatDoesNotSolve(challenge);

        // when
        HttpResponse<String> response = post("/api/v1/captcha/solve",
                "{\"challengeId\":\"" + challenge.jwt + "\",\"nonce\":\"" + wrongNonce + "\"}", null);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("pow_failed");
    }

    @Test
    @DisplayName("Легаси GET /api/v1/captcha больше не существует → 401 (вне белого списка)")
    void getCaptcha_legacyEndpoint_returnsUnauthorized() throws Exception {

        // given
        // when
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/captcha?capId=x"))).GET().build());

        // then
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── вспомогательные ──

    private record Challenge(String jwt, String salt, int difficulty) {
    }

    private Challenge challenge(String action) throws Exception {
        HttpResponse<String> response = post("/api/v1/captcha/challenge", "{\"action\":\"" + action + "\"}", null);
        assertThat(response.statusCode()).as("challenge выдаётся").isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("type").asText()).isEqualTo(ChallengeResponse.TYPE_POW);
        return new Challenge(body.get("challengeId").asText(), body.get("params").get("salt").asText(),
                body.get("params").get("difficulty").asInt());
    }

    private String solve(Challenge challenge) throws Exception {
        String nonce = null;
        for (long i = 0; i < 1_000_000L && nonce == null; i++) {
            String candidate = Long.toString(i);
            if (PowHash.meets(challenge.salt(), candidate, challenge.difficulty())) {
                nonce = candidate;
            }
        }
        assertThat(nonce).as("решение найдено в тестовом пределе").isNotNull();

        HttpResponse<String> response = post("/api/v1/captcha/solve",
                "{\"challengeId\":\"" + challenge.jwt() + "\",\"nonce\":\"" + nonce + "\"}", null);
        assertThat(response.statusCode()).as("solve принимает верное решение: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("captchaToken").asText();
    }

    /** Не-решение подбирается детерминированно — без шанса случайно набрать нули. */
    private String nonceThatDoesNotSolve(Challenge challenge) {
        for (long i = 0; i < 1_000_000L; i++) {
            String candidate = Long.toString(i);
            if (!PowHash.meets(challenge.salt(), candidate, challenge.difficulty())) {
                return candidate;
            }
        }
        throw new IllegalStateException("не-решение не найдено");
    }

    /** Бизнес-операция, защищённая капчей: выдача кода подтверждения email (нужен bearer). */
    private HttpResponse<String> sendCode(String captchaToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("/api/v1/email/codes")))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Authorization", "Bearer " + TestAuthenticationConfiguration.testToken())
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (captchaToken != null) {
            builder.header("X-Captcha-Token", captchaToken);
        }
        return send(builder.build());
    }

    private String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText(null);
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
}

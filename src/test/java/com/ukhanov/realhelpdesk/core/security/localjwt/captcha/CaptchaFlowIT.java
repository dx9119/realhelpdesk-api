package com.ukhanov.realhelpdesk.core.security.localjwt.captcha;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

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
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto.ChallengeResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.utils.PowHash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной поток PoW-капчи по реальному HTTP: challenge → решение в тесте (тот же PowHash, что на сервере) → solve → регистрация токеном.
 * Проверяются и отказы: replay токена, токен чужого действия, honeypot, неверный nonce, отсутствующий токен. Профиль test: капча включена,
 * difficulty=8 (решается за миллисекунды), minimum-fill=0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "email.notify=admin@example.com", "email.from=noreply@example.com",
                "email.domain=example.com"})
@DisplayName("PoW-капча: полный поток challenge → solve → register (HTTP)")
class CaptchaFlowIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTION_REGISTER = "register";

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
    @DisplayName("challenge → верный nonce → solve → регистрация с токеном: 201")
    void register_validCaptchaToken_returnsCreated() throws Exception {

        // given
        // when
        Challenge challenge = challenge(ACTION_REGISTER);
        String token = solve(challenge);

        HttpResponse<String> register = registerBody(registerJson(token, null, uniqueEmail()));

        // then
        assertThat(register.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Регистрация без captcha-токена → 400 с code=missing_token")
    void register_noCaptchaToken_returnsMissingToken() throws Exception {

        // given
        // when
        String body = registerJson(null, null, uniqueEmail());

        HttpResponse<String> response = registerBody(body);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("missing_token");
    }

    @Test
    @DisplayName("Повторное использование токена → 400 с code=replayed")
    void register_reusedCaptchaToken_returnsReplayed() throws Exception {

        // given
        // when
        String token = solve(challenge(ACTION_REGISTER));
        // then
        assertThat(registerBody(registerJson(token, null, uniqueEmail())).statusCode()).isEqualTo(201);

        HttpResponse<String> replay = registerBody(registerJson(token, null, uniqueEmail()));

        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(code(replay)).isEqualTo("replayed");
    }

    @Test
    @DisplayName("Токен действия email-code предъявлен на регистрацию → 400 с code=wrong_action")
    void register_tokenOfOtherAction_returnsWrongAction() throws Exception {

        // given
        // when
        String token = solve(challenge("email-code"));

        HttpResponse<String> response = registerBody(registerJson(token, null, uniqueEmail()));

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("wrong_action");
    }

    @Test
    @DisplayName("Заполненное honeypot-поле website → 400 с code=honeypot даже с валидным токеном")
    void register_filledHoneypot_returnsHoneypot() throws Exception {

        // given
        // when
        String token = solve(challenge(ACTION_REGISTER));

        HttpResponse<String> response = registerBody(registerJson(token, "https://spam.example", uniqueEmail()));

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("honeypot");
    }

    @Test
    @DisplayName("solve с нерешённым nonce → 400 с code=pow_failed")
    void solve_wrongNonce_returnsPowFailed() throws Exception {

        // given
        // when
        Challenge challenge = challenge(ACTION_REGISTER);
        String wrongNonce = nonceThatDoesNotSolve(challenge);

        HttpResponse<String> response = post("/api/v1/captcha/solve",
                "{\"challengeId\":\"" + challenge.jwt + "\",\"nonce\":\"" + wrongNonce + "\"}");

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("pow_failed");
    }

    @Test
    @DisplayName("Легаси GET /api/v1/captcha больше не существует → 401 (вне белого списка)")
    void getCaptcha_legacyEndpoint_returnsUnauthorized() throws Exception {

        // given
        // when
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url("/api/v1/captcha?capId=x"))).GET().build());

        // then
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── вспомогательные ──

    private record Challenge(String jwt, String salt, int difficulty) {
    }

    private Challenge challenge(String action) throws Exception {
        HttpResponse<String> response = post("/api/v1/captcha/challenge", "{\"action\":\"" + action + "\"}");
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
                "{\"challengeId\":\"" + challenge.jwt() + "\",\"nonce\":\"" + nonce + "\"}");
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

    private String registerJson(String captchaToken, String website, String email) {
        StringBuilder body = new StringBuilder(
                "{\"firstName\":\"Flow\",\"lastName\":\"Test\",\"email\":\"" + email + "\",\"password\":\"Sup3rSecret123!\"");
        if (captchaToken != null) {
            body.append(",\"captchaToken\":\"").append(captchaToken).append("\"");
        }
        if (website != null) {
            body.append(",\"website\":\"").append(website).append("\"");
        }
        return body.append("}").toString();
    }

    private HttpResponse<String> registerBody(String json) throws Exception {
        return post("/api/v1/auth/register", json);
    }

    private String uniqueEmail() {
        return "captcha-flow-" + UUID.randomUUID() + "@example.com";
    }

    private String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText(null);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
}

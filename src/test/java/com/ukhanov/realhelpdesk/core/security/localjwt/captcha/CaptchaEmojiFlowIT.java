package com.ukhanov.realhelpdesk.core.security.localjwt.captcha;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 * Сквозной поток эскалации на эмодзи-уровень по реальному HTTP: провалы с разных IP одной подсети доводят риск до
 * {@code captcha.risk-subnet-offender-threshold=2} (отдельный контекст приложения), следующий challenge приходит как эмодзи-задание,
 * неверный ответ отклоняется (wrong_answer), правильный ответ (подбирается локально по HMAC с тестовым секретом) решает captcha-токен и
 * завершается регистрацией 201. Клиентский IP берётся из {@code X-Forwarded-For} (доверие заголовкам включено в контексте), поэтому подсеть
 * задаётся тестом. Контекст свой — не мешает PoW-потоку CaptchaFlowIT с завышенным порогом.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "email.notify=admin@example.com", "email.from=noreply@example.com",
                "email.domain=example.com", "client-ip.trust-proxy-headers=true", "captcha.risk-subnet-offender-threshold=2"})
@DisplayName("Эмодзи-капча: эскалация по риску, wrong_answer и полный поток (HTTP)")
class CaptchaEmojiFlowIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTION_REGISTER = "register";
    /** Два разных IP одной подсети /24: второй провал доводит подсеть до порога распределённости. */
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String SECOND_CLIENT_IP = "203.0.113.11";
    /** Тестовый HMAC-секрет из application-test.properties — позволяет подобрать ответ локально, без перебора по HTTP. */
    private static final String TEST_SECRET = "test-only-captcha-hmac-secret-0123456789abcdef";

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
    @DisplayName("Провалы PoW → эмодзи-challenge → неверный ответ → правильный ответ → регистрация: 201")
    void register_escalatedChallengeWrongThenCorrect_returnsCreated() throws Exception {

        // given
        // when
        escalateRiskToEmoji();
        EmojiChallenge challenge = emojiChallenge(ACTION_REGISTER);

        HttpResponse<String> wrong = solveEmoji(challenge.jwt(), List.of(0, 1));
        List<Integer> answer = correctAnswer(challenge);
        HttpResponse<String> solved = solveEmoji(challenge.jwt(), answer);
        String token = JSON.readTree(solved.body()).get("captchaToken").asText();
        HttpResponse<String> register = post("/api/v1/auth/register", "{\"firstName\":\"Emoji\",\"lastName\":\"Flow\",\"email\":\""
                + uniqueEmail() + "\",\"password\":\"Sup3rSecret123!\",\"captchaToken\":\"" + token + "\"}");

        // then
        assertThat(wrong.statusCode()).as("ответ из двух ячеек никогда не бывает верным").isEqualTo(400);
        assertThat(code(wrong)).isEqualTo("wrong_answer");
        assertThat(solved.statusCode()).as("solve принимает верный ответ: %s", solved.body()).isEqualTo(200);
        assertThat(register.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("После провалов challenge приходит с prompt и сеткой 9 глифов без повторов")
    void challenge_afterEscalation_carriesEmojiGrid() throws Exception {

        // given
        // when
        escalateRiskToEmoji();

        EmojiChallenge challenge = emojiChallenge(ACTION_REGISTER);

        // then
        assertThat(challenge.prompt()).isNotBlank();
        assertThat(challenge.cells()).hasSize(9).doesNotHaveDuplicates();
    }

    // ── вспомогательные ──

    private record EmojiChallenge(String jwt, String challengeId, String prompt, List<String> cells, String answerHash) {
    }

    /**
     * Два провала с разных IP одной подсети доводят подсеть до порога распределённости. Уровень первого challenge зависит от состояния
     * общего контекста (предыдущий тест мог оставить подсеть рискованной) — провал засчитывается и pow_failed, и wrong_answer: любой отказ
     * двигает риск-счётчик. Для pow берётся детерминированное не-решение (nonce "0" на малой сложности мог бы случайно решить задание).
     */
    private void escalateRiskToEmoji() throws Exception {
        String[] offenders = {CLIENT_IP, SECOND_CLIENT_IP};
        for (String offender : offenders) {
            HttpResponse<String> challenge = post("/api/v1/captcha/challenge", "{\"action\":\"" + ACTION_REGISTER + "\"}", offender);
            assertThat(challenge.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(challenge.body());
            String jwt = body.get("challengeId").asText();

            String solveBody;
            if (ChallengeResponse.TYPE_EMOJI.equals(body.get("type").asText())) {
                // эмодзи-задание: пустой ответ никогда не верный
                solveBody = "{\"challengeId\":\"" + jwt + "\"}";
            } else {
                String nonce = nonceThatDoesNotSolve(body.get("params").get("salt").asText(), body.get("params").get("difficulty").asInt());
                solveBody = "{\"challengeId\":\"" + jwt + "\",\"nonce\":\"" + nonce + "\"}";
            }
            HttpResponse<String> failed = post("/api/v1/captcha/solve", solveBody, offender);
            assertThat(failed.statusCode()).as("детерминированное не-решение отклоняется").isEqualTo(400);
            assertThat(code(failed)).as("провал капчи на любом уровне двигает риск").isIn("pow_failed", "wrong_answer");
        }
    }

    /** Не-решение подбирается детерминированно — без шанса случайно набрать нули (как в CaptchaFlowIT). */
    private String nonceThatDoesNotSolve(String salt, int difficulty) {
        for (long i = 0; i < 1_000_000L; i++) {
            String candidate = Long.toString(i);
            if (!PowHash.meets(salt, candidate, difficulty)) {
                return candidate;
            }
        }
        throw new IllegalStateException("не-решение не найдено");
    }

    private EmojiChallenge emojiChallenge(String action) throws Exception {
        HttpResponse<String> response = post("/api/v1/captcha/challenge", "{\"action\":\"" + action + "\"}");
        assertThat(response.statusCode()).as("challenge выдаётся").isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("type").asText()).as("рискованный IP получает эмодзи-задание").isEqualTo(ChallengeResponse.TYPE_EMOJI);

        String jwt = body.get("challengeId").asText();
        JsonNode claims = jwtPayload(jwt);
        List<String> cells = new ArrayList<>();
        body.get("params").get("cells").forEach(cell -> cells.add(cell.asText()));
        return new EmojiChallenge(jwt, claims.get("cid").asText(), body.get("params").get("prompt").asText(), cells,
                claims.get("ah").asText());
    }

    /**
     * Правильный ответ: локальный перебор кандидатов (один глиф или три — ровно такие варианты генерирует фабрика) и сравнение HMAC с
     * подписанным в claims. Тестовый секрет известен, HTTP-перебор не нужен.
     */
    private List<Integer> correctAnswer(EmojiChallenge challenge) throws Exception {
        for (int i = 0; i < challenge.cells().size(); i++) {
            List<Integer> candidate = List.of(i);
            if (answerHash(challenge.challengeId(), candidate).equals(challenge.answerHash())) {
                return candidate;
            }
        }
        List<Integer> triple = new ArrayList<>();
        for (int i = 0; i < challenge.cells().size(); i++) {
            for (int j = i + 1; j < challenge.cells().size(); j++) {
                for (int k = j + 1; k < challenge.cells().size(); k++) {
                    triple.clear();
                    triple.add(i);
                    triple.add(j);
                    triple.add(k);
                    if (answerHash(challenge.challengeId(), triple).equals(challenge.answerHash())) {
                        return List.copyOf(triple);
                    }
                }
            }
        }
        throw new IllegalStateException("верный ответ эмодзи-задания не найден перебором");
    }

    /** Тот же алгоритм, что в CaptchaTokenService.answerHash: HMACSHA256(cid + ":" + отсортированные индексы через запятую), hex. */
    private String answerHash(String challengeId, List<Integer> answer) throws Exception {
        String canonical = answer.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((challengeId + ":" + canonical).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private HttpResponse<String> solveEmoji(String challengeJwt, List<Integer> answer) throws Exception {
        StringBuilder body = new StringBuilder("{\"challengeId\":\"").append(challengeJwt).append("\",\"answer\":[");
        body.append(answer.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        return post("/api/v1/captcha/solve", body.append("]}").toString());
    }

    private JsonNode jwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private String uniqueEmail() {
        return "emoji-flow-" + UUID.randomUUID() + "@example.com";
    }

    private String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText(null);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return post(path, json, CLIENT_IP);
    }

    private HttpResponse<String> post(String path, String json, String clientIp) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest").header("X-Forwarded-For", clientIp)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
}

package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GenTokenService;
import com.ukhanov.realhelpdesk.core.security.user.SecurityUser;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной поток операторской очистки: доступ только у {@code ROLE_OPERATOR}, удаление старых отозванных токенов с возвратом счётчиков.
 * Срок хранения отозванных занижен до суток (86400 с), токены состариваются bulk-запросом.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "jwt.revoked-token-retention-seconds=86400"})
@DisplayName("Операторская очистка отозванных токенов (HTTP)")
class OperatorTokenCleanupIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/operator/tokens/cleanup";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Instant OLD = Instant.now().minus(2, ChronoUnit.DAYS);

    @LocalServerPort
    private int serverPort;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtRefreshTokenRepository jwtRefreshTokenRepository;

    @Autowired
    private GenTokenService genTokenService;

    @Autowired
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private UserModel operator;
    private UserModel regular;
    private String operatorToken;
    private String regularToken;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jwtRefreshTokenRepository.deleteAll();
        operator = saveUser(UserRole.ROLE_OPERATOR);
        regular = saveUser(UserRole.ROLE_USER);
        operatorToken = accessToken(operator);
        regularToken = accessToken(regular);
    }

    @Test
    @DisplayName("Без access-токена — 401, очистка не запускается")
    void cleanup_withoutAccessToken_returns401() throws Exception {

        // given
        // when
        HttpResponse<String> response = cleanup(null);

        // then
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("С ролью ROLE_USER — 403, отозванные токены не удаляются")
    void cleanup_userRole_returns403AndKeepsRevoked() throws Exception {

        // given
        // when
        UUID revokedOld = saveRevoked(OLD);

        HttpResponse<String> response = cleanup(regularToken);

        // then
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jwtRefreshTokenRepository.findById(revokedOld)).as("обычный пользователь не запускает очистку").isPresent();
    }

    @Test
    @DisplayName("С ролью ROLE_OPERATOR — 200: старые отозванные удаляются, свежие и активные остаются")
    void cleanup_operatorRole_deletesOnlyOldRevoked() throws Exception {

        // given
        // when
        UUID revokedOldFirst = saveRevoked(OLD);
        UUID revokedOldSecond = saveRevoked(OLD);
        UUID revokedFresh = saveRevoked(Instant.now());
        UUID activeOld = save(TokenStatus.ACTIVE, OLD);

        HttpResponse<String> response = cleanup(operatorToken);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"revokedDeleted\":2").contains("\"batches\":1");
        assertThat(jwtRefreshTokenRepository.findById(revokedOldFirst)).isEmpty();
        assertThat(jwtRefreshTokenRepository.findById(revokedOldSecond)).isEmpty();
        assertThat(jwtRefreshTokenRepository.findById(revokedFresh)).as("свежие отозванные не трогаются").isPresent();
        assertThat(jwtRefreshTokenRepository.findById(activeOld)).as("активные токены не трогаются").isPresent();
    }

    private HttpResponse<String> cleanup(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + PATH))
                .header("X-Requested-With", "test").POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Cookie", "accessToken=" + token);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String accessToken(UserModel user) {
        return genTokenService.generateAccessJwtToken(new SecurityUser(user)).token();
    }

    private UserModel saveUser(UserRole role) {
        UserModel user = new UserModel();
        user.setFirstName("Тест");
        user.setLastName("Тестов");
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash");
        user.setUserRole(role);
        user.setUserStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private UUID saveRevoked(Instant createdAt) {
        return save(TokenStatus.REVOKED, createdAt);
    }

    private UUID save(TokenStatus status, Instant createdAt) {
        RefreshTokenModel token = new RefreshTokenModel();
        token.setTokenRefresh((UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""));
        token.setStatus(status);
        token.setUser(operator);
        jwtRefreshTokenRepository.saveAndFlush(token);
        // createdAt выставляет только @PrePersist и сеттера снаружи нет — состариваем bulk-запросом в отдельной транзакции
        transactionTemplate
                .executeWithoutResult(ignored -> em.createQuery("UPDATE RefreshTokenModel t SET t.createdAt = :ts WHERE t.uuid = :id")
                        .setParameter("ts", createdAt).setParameter("id", token.getUuid()).executeUpdate());
        return token.getUuid();
    }
}

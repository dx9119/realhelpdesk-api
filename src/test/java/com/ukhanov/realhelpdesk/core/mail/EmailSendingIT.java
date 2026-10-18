package com.ukhanov.realhelpdesk.core.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ukhanov.realhelpdesk.PostgresIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailLog;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.model.UnsubscribedEmail;
import com.ukhanov.realhelpdesk.core.mail.repository.EmailLogRepository;
import com.ukhanov.realhelpdesk.core.mail.repository.UnsubscribedEmailRepository;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailLogService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailPolicyService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.ratelimit.config.RateLimitProperties;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Реальная отправка email по SMTP (GreenMail + PostgreSQL)")
class EmailSendingIT extends PostgresIntegrationTest {

    private static final String RECIPIENT = "user@example.com";
    private static final String FROM = "noreply@example.com";
    private static final String ADMIN = "admin@example.com";

    private static GreenMail greenMail;

    @Autowired
    private EmailDeliveryService emailDeliveryService;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private UnsubscribedEmailRepository unsubscribedEmailRepository;

    @Autowired
    private UserDomainService userDomainService;

    @Autowired
    private EntityManager entityManager;

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
    void configureMailSender() {
        greenMail.reset();
        javaMailSender.setHost("localhost");
        javaMailSender.setPort(greenMail.getSmtp().getPort());
        javaMailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ────────────────────────────────────────────────
    // Отправка через SMTP
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Письмо реально доходит до SMTP-сервера с нужными заголовками и текстом")
    void sendEmail_deliversMessageThroughSmtp() throws Exception {
        emailDeliveryService.sendEmail(RECIPIENT, "Тема письма", "Текст письма", NotificationEvent.NEW_TICKET);

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage message = greenMail.getReceivedMessages()[0];

        assertThat(message.getSubject()).isEqualTo("Тема письма");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("Оповещения Заявус");
        assertThat(contentOf(message)).contains("Текст письма");
        assertThat(message.getHeader("Content-Type", null)).contains("charset=UTF-8");

        assertThat(countLogs(RECIPIENT, NotificationEvent.NEW_TICKET)).isEqualTo(1);
    }

    @Test
    @DisplayName("Письмо админу уходит на адрес email.notify")
    void sendAdminNotification_deliversToNotifyAddress() throws Exception {
        emailDeliveryService.sendAdminNotification("Новая регистрация", "Кто,что", NotificationEvent.NEW_SYSTEM_MESSAGE);

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString()).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("Адрес в стоп-листе: письмо не доставляется и не попадает в журнал")
    void sendEmail_recipientInStopList_isNotDeliveredAndNotLogged() throws Exception {
        unsubscribedEmailRepository.saveAndFlush(new UnsubscribedEmail(RECIPIENT, NotificationEvent.NEW_MESSAGE));

        emailDeliveryService.sendEmail(RECIPIENT, "Тема", "Текст", NotificationEvent.NEW_MESSAGE);
        emailDeliveryService.sendEmail(RECIPIENT, "Тема", "Текст", NotificationEvent.NEW_TICKET);

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(countLogs(RECIPIENT, NotificationEvent.NEW_MESSAGE)).isZero();
        assertThat(countLogs(RECIPIENT, NotificationEvent.NEW_TICKET)).isEqualTo(1);
    }

    @Test
    @DisplayName("Лимит восстановления пароля не исчерпан — письмо доставляется и учитывается в журнале")
    void sendEmail_recoveryPasswordWithinLimit_isDelivered() throws Exception {
        seedRecoveryLogs(2);

        emailDeliveryService.sendEmail(RECIPIENT, "Сброс пароля", "Текст", NotificationEvent.RECOVERY_PASSWORD);

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(countLogs(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD)).isEqualTo(3);
    }

    @Test
    @DisplayName("Сверх лимита восстановления пароля — 429, письмо не доставляется и не логируется")
    void sendEmail_recoveryPasswordOverLimit_throwsTooManyRequests() {
        seedRecoveryLogs(3);

        assertThatThrownBy(() -> emailDeliveryService.sendEmail(RECIPIENT, "Сброс пароля", "Текст", NotificationEvent.RECOVERY_PASSWORD))
                .isInstanceOf(EmailAccessDeniedException.class).extracting(ex -> ((EmailAccessDeniedException) ex).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(countLogs(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD)).isEqualTo(3);
    }

    private void seedRecoveryLogs(int count) {
        for (int i = 0; i < count; i++) {
            emailLogRepository.saveAndFlush(new EmailLog("Сброс пароля", FROM, RECIPIENT, NotificationEvent.RECOVERY_PASSWORD));
        }
    }

    // ────────────────────────────────────────────────
    // Подтверждение адреса реальным пользователем
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Верный код подтверждения помечает адрес подтверждённым в БД")
    void confirmEmail_correctToken_persistsVerifiedFlag() {
        UserModel user = saveUser(123456789L);
        authenticateAs(user);

        emailDeliveryService.confirmEmail(123456789L);
        entityManager.flush();
        entityManager.clear();

        assertThat(userDomainService.getUserById(user.getId()).isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("Неверный код подтверждения — 403 и флаг в БД не меняется")
    void confirmEmail_wrongToken_keepsFlagUnchanged() {
        UserModel user = saveUser(123456789L);
        authenticateAs(user);

        assertThatThrownBy(() -> emailDeliveryService.confirmEmail(987654321L)).isInstanceOf(EmailAccessDeniedException.class);
        entityManager.flush();
        entityManager.clear();

        assertThat(userDomainService.getUserById(user.getId()).isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("Код подтверждения доходит до SMTP-сервера адресату")
    void sendConfirmCode_deliversCodeThroughSmtp() throws Exception {
        UserModel user = saveUser(424242L);
        authenticateAs(user);

        emailDeliveryService.sendConfirmCode();

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage message = greenMail.getReceivedMessages()[0];

        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo("Код подтверждения");
        assertThat(contentOf(message)).contains("424242");
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private long countLogs(String recipient, NotificationEvent event) {
        LocalDateTime now = LocalDateTime.now();
        return emailLogRepository.countEmailsByToAndEventBetween(recipient, event, now.minusMinutes(5), now.plusMinutes(1));
    }

    private UserModel saveUser(Long verifyEmailToken) {
        UserModel user = new UserModel();
        user.setFirstName("Имя");
        user.setLastName("Фамилия");
        user.setEmail(RECIPIENT);
        user.setPasswordHash("hash");
        user.setVerifyEmailToken(verifyEmailToken);
        return userDomainService.saveUser(user);
    }

    private void authenticateAs(UserModel user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, List.of()));
    }

    private static String contentOf(MimeMessage message) {
        try {
            Object content = message.getContent();
            if (content instanceof String text) {
                return text;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            message.writeTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class MailTestConfiguration {

        @Bean
        JavaMailSenderImpl javaMailSender() {
            return new JavaMailSenderImpl();
        }

        @Bean
        EmailProperties emailProperties() {
            EmailProperties properties = EmailTemplatesFixture.emailProperties();
            properties.setFrom(FROM);
            properties.setNotify(ADMIN);
            return properties;
        }

        @Bean
        RateLimitProperties rateLimitProperties() {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setLimits(Map.of("email-recovery", new RateLimitProperties.Limit(3, 86400)));
            return properties;
        }

        @Bean
        UserDomainService userDomainService(UserRepository userRepository) {
            return new UserDomainService(userRepository);
        }

        @Bean
        CurrentUserProvider currentUserProvider(UserDomainService userDomainService) {
            return new CurrentUserProvider(userDomainService);
        }

        @Bean
        EmailPolicyService emailPolicyService(UnsubscribedEmailRepository repository, CurrentUserProvider currentUserProvider) {
            return new EmailPolicyService(repository, currentUserProvider);
        }

        @Bean
        EmailLogService emailLogService(EmailLogRepository emailLogRepository) {
            return new EmailLogService(emailLogRepository);
        }

        @Bean
        EmailTemplates emailTemplates(EmailProperties emailProperties) {
            return new EmailTemplates(EmailTemplatesFixture.messageSource(), emailProperties);
        }

        @Bean
        EmailDeliveryService emailDeliveryService(JavaMailSender mailSender, UserDomainService userDomainService,
                EmailProperties emailProperties, EmailPolicyService emailPolicyService, CurrentUserProvider currentUserProvider,
                EmailLogService emailLogService, RateLimitProperties rateLimitProperties, EmailTemplates emailTemplates) {
            // NOOP-реестр: слайсу @DataJpaTest наблюдаемость не нужна, тесту важна только SMTP-логика.
            return new EmailDeliveryService(mailSender, userDomainService, emailProperties, emailPolicyService, currentUserProvider,
                    emailLogService, rateLimitProperties, emailTemplates, ObservationRegistry.NOOP,
                    new BusinessMetrics(new SimpleMeterRegistry()));
        }
    }
}

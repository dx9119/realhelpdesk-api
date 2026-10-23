package com.ukhanov.realhelpdesk.core.mail.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailLog;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.ratelimit.config.RateLimitProperties;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

@DisplayName("Отправка писем: EmailDeliveryService")
class EmailDeliveryServiceTest {

    private static final String RECIPIENT = "user@example.com";
    private static final String ADMIN = "admin@example.com";
    private static final String FROM = "noreply@example.com";

    private JavaMailSender mailSender;
    private UserDomainService userDomainService;
    private EmailProperties emailProperties;
    private EmailPolicyService emailPolicyService;
    private CurrentUserProvider currentUserProvider;
    private EmailLogService emailLogService;
    private RateLimitProperties rateLimitProperties;
    private EmailDeliveryService service;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        userDomainService = mock(UserDomainService.class);
        emailPolicyService = mock(EmailPolicyService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        emailLogService = mock(EmailLogService.class);

        emailProperties = new EmailProperties();
        emailProperties.setFrom(FROM);
        emailProperties.setNotify(ADMIN);

        rateLimitProperties = new RateLimitProperties();
        rateLimitProperties.setLimits(Map.of("email-recovery", new RateLimitProperties.Limit(3, 86400)));

        service = new EmailDeliveryService(mailSender, userDomainService, emailProperties, emailPolicyService,
                currentUserProvider,
                emailLogService, rateLimitProperties, emailTemplates, ObservationRegistry.NOOP,
                new BusinessMetrics(meterRegistry));

        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
        // рассылка грузит получателей батчем: возвращаем только запрошенных из stubbedUsers
        when(userDomainService.getUsersByIds(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> requested = invocation.getArgument(0);
            Map<Long, UserModel> result = new java.util.HashMap<>();
            if (requested != null) {
                for (Long requestedId : requested) {
                    UserModel stubbed = stubbedUsers.get(requestedId);
                    if (stubbed != null) {
                        result.put(requestedId, stubbed);
                    }
                }
            }
            return result;
        });
    }

    // ────────────────────────────────────────────────
    // sendEmail
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Письмо собирается в MIME, отправляется и пишется в журнал")
    void sendEmail_validInput_buildsMimeAndWritesLog() throws Exception {

        // given
        // when
        service.sendEmail(RECIPIENT, "Тема письма", "Текст письма", NotificationEvent.NEW_TICKET);

        MimeMessage message = singleSentMessage();

        // then
        assertThat(message.getSubject()).isEqualTo("Тема письма");
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("Оповещения Заявус");
        assertThat(contentOf(message)).contains("Текст письма");
        assertThat(message.getHeader("MIME-Version", null)).isEqualTo("1.0");
        assertThat(message.getHeader("Content-Type", null)).contains("text/plain").contains("charset=UTF-8");
        assertThat(message.getHeader("Content-Transfer-Encoding", null)).isEqualTo("7bit");

        verify(emailLogService).add(argThat(EmailDeliveryServiceTest::matchesExpectedEmailLog));

        Counter success = meterRegistry.find("mail.sent").tags("type", "NEW_TICKET", "result", "success").counter();
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ошибка SMTP → mail.sent{result=failure} и исключение наружу")
    void sendEmail_smtpFailure_countsFailedMetric() {

        // given
        // when
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));

        MailSendException exception = catchThrowableOfType(MailSendException.class,
                () -> service.sendEmail(RECIPIENT, "Тема письма", "Текст письма", NotificationEvent.NEW_TICKET));

        // then
        assertThat(exception).isNotNull();
        Counter failure = meterRegistry.find("mail.sent").tags("type", "NEW_TICKET", "result", "failure").counter();
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1);
        assertThat(meterRegistry.find("mail.sent").tags("result", "success").counter()).isNull();
    }

    @Test
    @DisplayName("Адрес в стоп-листе: письмо не отправляется и не попадает в журнал")
    void sendEmail_recipientInStopList_skipsDeliveryAndLog() throws Exception {

        // given
        // when
        when(emailPolicyService.isStopList(RECIPIENT, NotificationEvent.NEW_MESSAGE)).thenReturn(true);

        service.sendEmail(RECIPIENT, "Тема", "Текст", NotificationEvent.NEW_MESSAGE);

        // then
        verify(emailLogService, never()).add(any(EmailLog.class));
        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
        // стоп-лист не отправлял письмо — попыток и неудач в метрике нет
        assertThat(meterRegistry.find("mail.sent").counter()).isNull();
    }

    @Test
    @DisplayName("Стоп-лист по событию не мешает отправке другого события")
    void sendEmail_otherEventNotMuted_isDelivered() throws Exception {

        // given
        // when
        when(emailPolicyService.isStopList(RECIPIENT, NotificationEvent.NEW_MESSAGE)).thenReturn(true);

        service.sendEmail(RECIPIENT, "Тема", "Текст", NotificationEvent.RECOVERY_PASSWORD);

        // then
        assertThat(sentMessages()).hasSize(1);
    }

    @Test
    @DisplayName("Восстановление пароля: отправлено меньше лимита — письмо уходит")
    void sendEmail_recoveryPasswordWithinLimit_isDelivered() throws Exception {

        // given
        // when
        when(emailLogService.countEmailsSentToByEventInWindow(eq(RECIPIENT), eq(NotificationEvent.RECOVERY_PASSWORD),
                eq(Duration.ofSeconds(86400)))).thenReturn(2L);

        service.sendEmail(RECIPIENT, emailTemplates.passwordResetSubject(), "Текст",
                NotificationEvent.RECOVERY_PASSWORD);

        // then
        assertThat(sentMessages()).hasSize(1);
    }

    @Test
    @DisplayName("Восстановление пароля: лимит исчерпан — 429, письмо не уходит и в журнал не пишется")
    void sendEmail_recoveryPasswordOverLimit_throwsTooManyRequestsWithoutDelivery() {

        // given
        // when
        when(emailLogService.countEmailsSentToByEventInWindow(eq(RECIPIENT), eq(NotificationEvent.RECOVERY_PASSWORD),
                eq(Duration.ofSeconds(86400)))).thenReturn(3L);

        EmailAccessDeniedException exception = catchThrowableOfType(EmailAccessDeniedException.class,
                () -> service.sendEmail(RECIPIENT, emailTemplates.passwordResetSubject(), "Текст",
                        NotificationEvent.RECOVERY_PASSWORD));

        // then
        assertThat(exception).isNotNull();
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exception.getMessage()).contains("86400");
        verify(emailLogService, never()).add(any(EmailLog.class));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Событие, отличное от восстановления пароля, лимитер не трогает")
    void sendEmail_otherEvent_doesNotTouchRateLimiter() throws Exception {

        // given
        // when
        service.sendEmail(RECIPIENT, "Тема", "Текст", NotificationEvent.NEW_TICKET);

        // then
        verify(emailLogService, never()).countEmailsSentToByEventInWindow(anyString(), any(), any());
        assertThat(sentMessages()).hasSize(1);
    }

    // ────────────────────────────────────────────────
    // sendAdminNotification / sendUserNotification
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Админское уведомление уходит на email.notify из конфига")
    void sendAdminNotification_configuredNotify_targetsAdminAddress() throws Exception {

        // given
        // when
        service.sendAdminNotification("Тема", "Текст", NotificationEvent.NEW_SYSTEM_MESSAGE);

        // then
        assertThat(recipientsOfSentMessages()).containsExactly(ADMIN);
    }

    @Test
    @DisplayName("Пользовательское уведомление уходит на адрес получателя")
    void sendUserNotification_validRecipient_targetsUserAddress() throws Exception {

        // given
        // when
        service.sendUserNotification(RECIPIENT, "Тема", "Текст", NotificationEvent.NEW_MESSAGE);

        // then
        assertThat(recipientsOfSentMessages()).containsExactly(RECIPIENT);
    }

    // ────────────────────────────────────────────────
    // initNotifyPortalUsers
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Уведомление по порталу уходит владельцу и всем допущенным пользователям")
    void initNotifyPortalUsers_portalWithAllowedUsers_notifiesOwnerAndMembers() throws Exception {

        // given
        // when
        PortalModel portal = portalWith(1L, Set.of(2L, 3L));
        stubUser(1L, "owner@example.com");
        stubUser(2L, "second@example.com");
        stubUser(3L, "third@example.com");

        service.initNotifyPortalUsers(portal, "Тема", "Текст", NotificationEvent.NEW_TICKET);

        // then
        assertThat(recipientsOfSentMessages()).containsExactlyInAnyOrder("owner@example.com", "second@example.com",
                "third@example.com");
        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Без списка допущенных пользователей письмо уходит только владельцу портала")
    void initNotifyPortalUsers_withoutAllowedUsers_sendsOnlyToOwner() throws Exception {

        // given
        // when
        PortalModel portal = portalWith(1L, null);
        stubUser(1L, "owner@example.com");

        service.initNotifyPortalUsers(portal, "Тема", "Текст", NotificationEvent.NEW_PORTAL);

        // then
        assertThat(recipientsOfSentMessages()).containsExactly("owner@example.com");
    }

    // ────────────────────────────────────────────────
    // confirmEmail
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Неверный код подтверждения — 403, пользователь не сохраняется")
    void confirmEmail_wrongToken_throwsForbidden() {

        // given
        // when
        UserModel user = currentUserWithToken(111L);

        EmailAccessDeniedException exception = catchThrowableOfType(EmailAccessDeniedException.class,
                () -> service.confirmEmail(222L));

        // then
        assertThat(exception).isNotNull();
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(user.isEmailVerified()).isFalse();
        verify(userDomainService, never()).saveUser(any());
    }

    @Test
    @DisplayName("Верный код подтверждения — адрес помечается подтверждённым и сохраняется")
    void confirmEmail_correctToken_marksEmailVerified() {

        // given
        // when
        UserModel user = currentUserWithToken(111L);

        service.confirmEmail(111L);

        // then
        assertThat(user.isEmailVerified()).isTrue();
        verify(userDomainService).saveUser(user);
    }

    // ────────────────────────────────────────────────
    // sendConfirmCode
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Код подтверждения уходит текущему пользователю с телом из шаблона")
    void sendConfirmCode_currentUserWithToken_sendsCodeFromTemplate() throws Exception {

        // given
        // when
        currentUserWithToken(987654L);

        service.sendConfirmCode();

        MimeMessage message = singleSentMessage();
        // then
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo(emailTemplates.registrationCodeSubject());
        assertThat(contentOf(message)).contains("987654");

        verify(emailLogService).add(argThat((EmailLog log) -> RECIPIENT.equals(log.getRecipient())
                && log.getNotificationEvent() == NotificationEvent.NEW_SYSTEM_MESSAGE));
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private List<MimeMessage> sentMessages() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private MimeMessage singleSentMessage() {
        List<MimeMessage> messages = sentMessages();
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }

    private List<String> recipientsOfSentMessages() throws Exception {
        List<String> recipients = new ArrayList<>();
        for (MimeMessage message : sentMessages()) {
            recipients.add(message.getAllRecipients()[0].toString());
        }
        return recipients;
    }

    private static String contentOf(MimeMessage message) throws IOException, MessagingException {
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        message.writeTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private final Map<Long, UserModel> stubbedUsers = new java.util.HashMap<>();

    private void stubUser(Long id, String email) {
        UserModel user = new UserModel();
        user.setId(id);
        user.setEmail(email);
        stubbedUsers.put(id, user);
    }

    private UserModel currentUserWithToken(Long token) {
        UserModel user = new UserModel();
        user.setId(42L);
        user.setEmail(RECIPIENT);
        user.setVerifyEmailToken(token);
        when(currentUserProvider.getCurrentUserModel()).thenReturn(user);
        return user;
    }

    private PortalModel portalWith(Long ownerId, Set<Long> allowedUserIds) {
        UserModel owner = new UserModel();
        owner.setId(ownerId);

        PortalModel portal = new PortalModel();
        portal.setOwner(owner);
        portal.setAllowedUserIds(allowedUserIds == null ? null : new HashSet<>(allowedUserIds));
        return portal;
    }

    private static boolean matchesExpectedEmailLog(EmailLog log) {
        boolean subjectAndSender = "Тема письма".equals(log.getTitle()) && FROM.equals(log.getSender());
        boolean recipientAndEvent = RECIPIENT.equals(log.getRecipient())
                && log.getNotificationEvent() == NotificationEvent.NEW_TICKET;
        return subjectAndSender && recipientAndEvent && log.getSentAt() != null;
    }
}

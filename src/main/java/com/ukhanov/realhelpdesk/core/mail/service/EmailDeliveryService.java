package com.ukhanov.realhelpdesk.core.mail.service;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailLog;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.ratelimit.config.RateLimitProperties;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class EmailDeliveryService {

    private final JavaMailSender mailSender;
    private final UserDomainService userDomainService;
    private final EmailProperties emailProperties;
    private final EmailPolicyService emailPolicyService;
    private final CurrentUserProvider currentUserProvider;
    private final EmailLogService emailLogService;
    private final RateLimitProperties rateLimitProperties;
    private final EmailTemplates emailTemplates;
    private final ObservationRegistry observationRegistry;
    private final BusinessMetrics businessMetrics;

    public void sendEmail(String recipient, String subject, String text, NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(recipient, subject, text, sourceEvent, null);
    }

    /**
     * То же, но с получателем-пользователем: в prod-логе видно, кому ушло письмо, при этом сам адрес остаётся только в
     * DEBUG (PII в prod не тащим).
     */
    public void sendEmail(String recipient, String subject, String text, NotificationEvent sourceEvent, Long userId)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {

        if (sourceEvent == NotificationEvent.RECOVERY_PASSWORD) {
            emailLimiter(recipient);
        }

        if (emailPolicyService.isStopList(recipient, sourceEvent)) {
            logger.debug("Письмо не отправлено: адрес в стоп-листе, event={}", sourceEvent);
            return;
        }

        emailLogService.add(deliver(recipient, subject, text, sourceEvent, userId));
    }

    /** SMTP + INFO-лог отправки; журнал пишет вызывающий — одиночной записью или пачкой после рассылки. */
    private EmailLog deliver(String recipient, String subject, String text, NotificationEvent sourceEvent, Long userId)
            throws MessagingException, UnsupportedEncodingException {

        Observation observation = Observation.createNotStarted("mail.send", observationRegistry)
                .lowCardinalityKeyValue("operation", "send")
                .lowCardinalityKeyValue("event", sourceEvent.name())
                .start();
        try {
            logger.debug("Отправка письма, event={}, recipient={}", sourceEvent, LogSanitizer.email(recipient));

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");

            helper.setFrom(emailProperties.getFrom(), "Оповещения Заявус");
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(text, false); // false = plain text

            mimeMessage.setHeader("MIME-Version", "1.0");
            mimeMessage.setHeader("Content-Type", "text/plain; charset=UTF-8");
            mimeMessage.setHeader("Content-Transfer-Encoding", "7bit");

            mailSender.send(mimeMessage);
            businessMetrics.mailSent(sourceEvent, true);
            if (userId != null) {
                logger.info("Письмо отправлено, event={}, userId={}", sourceEvent, userId);
            } else {
                logger.info("Письмо отправлено, event={}", sourceEvent);
            }
            observation.stop();

            // журнал — только фактически отправленные письма: он же считает квоту для emailLimiter
            return new EmailLog(subject, emailProperties.getFrom(), recipient, sourceEvent);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // MailException (ошибка SMTP) тоже останавливает наблюдение: mail.sent должен считать и неудачи
            businessMetrics.mailSent(sourceEvent, false);
            observation.lowCardinalityKeyValue("outcome", "error").stop();
            throw e;
        }
    }

    public void emailLimiter(String recipient) {
        RateLimitProperties.Limit limit = rateLimitProperties.require("email-recovery");
        long count = emailLogService.countEmailsSentToByEventInWindow(recipient, NotificationEvent.RECOVERY_PASSWORD,
                Duration.ofSeconds(limit.windowSeconds()));

        // записи в журнале предшествующих отправок уже учтены, поэтому лимит — «не больше requests в окно»
        // цифры нужны, чтобы объяснить 429 «исчерпан лимит восстановления»
        logger.debug("Писем по событию {} за окно {} сек: {} (лимит {})", NotificationEvent.RECOVERY_PASSWORD,
                limit.windowSeconds(), count,
                limit.requests());

        if (count >= limit.requests()) {
            throw new EmailAccessDeniedException(
                    "Исчерпан лимит на количество запросов восстановления, окно лимита " + limit.windowSeconds()
                            + " сек",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

    }

    public void sendAdminNotification(String subject, String text, NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(emailProperties.getNotify(), subject, text, sourceEvent);
    }

    public void sendUserNotification(String userEmail, String subject, String text, NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(userEmail, subject, text, sourceEvent);
    }

    /** Письмо конкретному пользователю: userId попадает в prod-лог, адрес остаётся в DEBUG. */
    public void sendUserNotification(String userEmail, String subject, String text, NotificationEvent sourceEvent,
            Long userId)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(userEmail, subject, text, sourceEvent, userId);
    }

    public void initNotifyPortalUsers(PortalModel portal, String subject, String message, NotificationEvent sourceEvent)
            throws MessagingException, UnsupportedEncodingException {
        Set<Long> portalUsers = new HashSet<>(Set.of(portal.getOwner().getId()));
        // Проверяем, что есть внешние пользователи
        if (portal.getAllowedUserIds() != null) {
            portalUsers.addAll(portal.getAllowedUserIds());
        }

        sendPortalUsersNotification(portalUsers, subject, message, sourceEvent);

    }

    public void sendPortalUsersNotification(Set<Long> userIds, String subject, String text,
            NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {

        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        // Пользователи и стоп-листы грузятся батчами (один запрос каждый) вместо N+1 по рассылке
        Map<Long, UserModel> usersById = userDomainService.getUsersByIds(userIds);
        List<UserModel> recipients = new ArrayList<>();
        for (Long userId : userIds) {
            UserModel user = usersById.get(userId);
            if (user == null) {
                // Удалённый участник не должен обрывать рассылку остальным
                logger.warn("Письмо не отправлено: получатель {} не найден в БД", userId);
                continue;
            }
            recipients.add(user);
        }

        Set<String> emails = recipients.stream().map(UserModel::getEmail).collect(Collectors.toSet());
        Map<String, NotificationEvent> muteEvents = emailPolicyService.muteEventsByEmails(emails);

        List<EmailLog> logs = new ArrayList<>();
        for (UserModel user : recipients) {
            if (emailPolicyService.isMuted(muteEvents.get(user.getEmail()), sourceEvent)) {
                // Отписка конкретного получателя — не повод не отправить письмо остальным
                logger.debug("Письмо получателю {} не отправлено: адрес в стоп-листе, event={}", user.getId(),
                        sourceEvent);
                continue;
            }
            logs.add(deliver(user.getEmail(), subject, text, sourceEvent, user.getId()));
        }

        // журнал рассылки — одной пачкой
        emailLogService.addAll(logs);
    }

    public void confirmEmail(Long token) throws EmailAccessDeniedException {
        UserModel user = currentUserProvider.getCurrentUserModel();
        if (!user.getVerifyEmailToken().equals(token)) {
            throw new EmailAccessDeniedException("Код подтверждения права владения почтой не верный");
        }
        user.setEmailVerified(true);
        userDomainService.saveUser(user);
        // INFO-след об успешном подтверждении обязателен: иначе в логе виден только неверный код (WARN в advice)
        logger.info("Email подтверждён, userId={}", user.getId());
    }

    public void sendConfirmCode() throws MessagingException, UnsupportedEncodingException {
        UserModel user = currentUserProvider.getCurrentUserModel();

        sendUserNotification(user.getEmail(), emailTemplates.registrationCodeSubject(),
                emailTemplates.registrationCodeBody(user.getVerifyEmailToken().toString()),
                NotificationEvent.NEW_SYSTEM_MESSAGE,
                user.getId());
        // Код подтверждения уходит как NEW_SYSTEM_MESSAGE, поэтому отличить его от системного письма
        // по строке «Письмо отправлено, event=…» нельзя — добавляем отдельную запись (B4)
        logger.info("Отправлен код подтверждения email, userId={}", user.getId());
    }

}

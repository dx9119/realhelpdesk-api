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
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailTemplateRenderService.EmailContent;
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

/**
 * Доставка HTML-писем: рендер шаблона, MIME, журнал, метрики.
 *
 * <p>
 * Вызывающий передаёт тип письма и модель параметров — тему и тело формирует почтовый слой, тексты писем в конфигурации
 * не хранятся. Модель в лог не попадает: в письмах есть коды подтверждения, ссылки сброса и причины передачи портала.
 * </p>
 *
 * <p>
 * Рендер выполняется один раз на письмо, а не на получателя: рассылка по участникам портала отправляет один и тот же
 * HTML, и N рендеров одного шаблона были бы чистой потерей CPU под нагрузкой.
 * </p>
 *
 * <p>
 * Стоп-лист, mute и лимит восстановления проверяются до рендера — письмо, которое не будет отправлено, не должно
 * тратить сборку шаблона. Обе метрики ({@code mail.sent} и {@code email.template.sent}) считаются только по фактическим
 * попыткам доставки.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class EmailDeliveryService {

    private static final String FROM_PERSONAL = "Оповещения Заявус";

    private final JavaMailSender mailSender;
    private final UserDomainService userDomainService;
    private final EmailProperties emailProperties;
    private final EmailPolicyService emailPolicyService;
    private final CurrentUserProvider currentUserProvider;
    private final EmailLogService emailLogService;
    private final RateLimitProperties rateLimitProperties;
    private final EmailTemplateRenderService emailTemplateRenderService;
    private final ObservationRegistry observationRegistry;
    private final BusinessMetrics businessMetrics;

    /**
     * Письмо адресату по типу шаблона: в prod-логе видно тип письма и получателя, адрес остаётся только в DEBUG (PII в
     * prod не тащим).
     */
    public void sendEmail(String recipient, EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(recipient, template, model, sourceEvent, null);
    }

    /** То же, но с получателем-пользователем: {@code userId} попадает в prod-лог вместо адреса. */
    public void sendEmail(String recipient, EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent, Long userId)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {

        if (sourceEvent == NotificationEvent.RECOVERY_PASSWORD) {
            emailLimiter(recipient);
        }

        if (emailPolicyService.isStopList(recipient, sourceEvent)) {
            logger.debug("Письмо не отправлено: адрес в стоп-листе, template={}, event={}", template.name(),
                    sourceEvent);
            return;
        }

        emailLogService.add(deliver(recipient, render(template, model), template, sourceEvent, userId));
    }

    public void sendAdminNotification(EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(emailProperties.getNotify(), template, model, sourceEvent);
    }

    public void sendUserNotification(String userEmail, EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(userEmail, template, model, sourceEvent, null);
    }

    /** Письмо конкретному пользователю: userId попадает в prod-лог, адрес остаётся в DEBUG. */
    public void sendUserNotification(String userEmail, EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent, Long userId)
            throws MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        sendEmail(userEmail, template, model, sourceEvent, userId);
    }

    /** Рассылка владельцу и допущенным пользователям портала. */
    public void initNotifyPortalUsers(PortalModel portal, EmailTemplateId template, Map<String, Object> model,
            NotificationEvent sourceEvent)
            throws MessagingException, UnsupportedEncodingException {
        Set<Long> portalUsers = new HashSet<>(Set.of(portal.getOwner().getId()));
        if (portal.getAllowedUserIds() != null) {
            portalUsers.addAll(portal.getAllowedUserIds());
        }

        sendPortalUsersNotification(portalUsers, template, model, sourceEvent);
    }

    public void sendPortalUsersNotification(Set<Long> userIds, EmailTemplateId template, Map<String, Object> model,
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

        List<UserModel> deliverable = new ArrayList<>();
        for (UserModel user : recipients) {
            if (emailPolicyService.isMuted(muteEvents.get(user.getEmail()), sourceEvent)) {
                // Отписка конкретного получателя — не повод не отправить письмо остальным
                logger.debug("Письмо получателю {} не отправлено: адрес в стоп-листе, template={}, event={}",
                        user.getId(), template.name(), sourceEvent);
                continue;
            }
            deliverable.add(user);
        }

        if (deliverable.isEmpty()) {
            return;
        }

        // Шаблон рендерится один раз: всем получателям уходит один и тот же HTML
        EmailContent content = render(template, model);

        List<EmailLog> logs = new ArrayList<>();
        for (UserModel user : deliverable) {
            logs.add(deliver(user.getEmail(), content, template, sourceEvent, user.getId()));
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

        sendUserNotification(user.getEmail(), EmailTemplateId.REGISTRATION_CODE,
                Map.of("code", String.valueOf(user.getVerifyEmailToken())),
                NotificationEvent.NEW_SYSTEM_MESSAGE,
                user.getId());
        // Код подтверждения уходит как NEW_SYSTEM_MESSAGE, поэтому отличить его от системного письма
        // по строке «Письмо отправлено, event=…» нельзя — добавляем отдельную запись (B4)
        logger.info("Отправлен код подтверждения email, userId={}", user.getId());
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

    private EmailContent render(EmailTemplateId template, Map<String, Object> model) {
        logger.debug("Рендер письма, template={}", template.name());
        return emailTemplateRenderService.render(template, model);
    }

    /**
     * SMTP + INFO-лог отправки; журнал пишет вызывающий — одиночной записью или пачкой после рассылки.
     *
     * <p>
     * Кириллица в теме и теле кодирует JavaMail (UTF-8 + quoted-printable/base64 по содержимому), поэтому
     * {@code Content-Transfer-Encoding} вручную не проставляется: {@code saveChanges} при отправке пересчитывает его
     * сам, а ручная установка дала бы расхождение заголовка с фактическим байтовым представлением.
     * </p>
     */
    private EmailLog deliver(String recipient, EmailContent content, EmailTemplateId template,
            NotificationEvent sourceEvent, Long userId)
            throws MessagingException, UnsupportedEncodingException {

        Observation observation = Observation.createNotStarted("mail.send", observationRegistry)
                .lowCardinalityKeyValue("operation", "send")
                .lowCardinalityKeyValue("event", sourceEvent.name())
                .lowCardinalityKeyValue("template", template.name())
                .start();
        try {
            logger.debug("Отправка письма, template={}, event={}, recipient={}", template.name(), sourceEvent,
                    LogSanitizer.email(recipient));

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");

            helper.setFrom(emailProperties.getFrom(), FROM_PERSONAL);
            helper.setTo(recipient);
            helper.setSubject(content.subject());
            helper.setText(content.html(), true);

            mailSender.send(mimeMessage);
            businessMetrics.mailSent(sourceEvent, true);
            businessMetrics.emailTemplateSent(template, true);
            if (userId != null) {
                logger.info("Письмо отправлено, template={}, event={}, userId={}", template.name(), sourceEvent,
                        userId);
            } else {
                logger.info("Письмо отправлено, template={}, event={}", template.name(), sourceEvent);
            }
            observation.stop();

            // журнал — только фактически отправленные письма: он же считает квоту для emailLimiter
            return new EmailLog(content.subject(), emailProperties.getFrom(), recipient, sourceEvent);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // MailException (ошибка SMTP) тоже останавливает наблюдение: обе метрики должны считать и неудачи
            businessMetrics.mailSent(sourceEvent, false);
            businessMetrics.emailTemplateSent(template, false);
            observation.lowCardinalityKeyValue("outcome", "error").stop();
            throw e;
        }
    }

}

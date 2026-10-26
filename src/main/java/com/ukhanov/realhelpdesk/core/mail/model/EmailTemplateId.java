package com.ukhanov.realhelpdesk.core.mail.model;

import java.util.Map;
import java.util.Set;

/**
 * Типы HTML-писем системы: путь к Thymeleaf-шаблону и шаблон темы.
 *
 * <p>
 * {@code EmailTemplateId} ≠ {@link NotificationEvent}: у события {@code PORTAL_TRANSFER_REQUESTED} два разных письма
 * (участникам портала и кандидату), поэтому сопоставление «событие → письмо» не одно к одному. NotificationEvent
 * отвечает за стоп-листы, mute и метрику {@code mail.sent}, EmailTemplateId — за содержание письма и метрику
 * {@code email.template.sent}.
 * </p>
 *
 * <p>
 * Тема — шаблон с плейсхолдерами {@code {key}}; ключи обязана содержать модель, переданная вызывающим (проверяет
 * EmailTemplateIdTest). Плейсхолдеры {@code {projectName}} резолвятся общими значениями рендерера.
 * </p>
 */
public enum EmailTemplateId {

    REGISTRATION_LINK("mail/registration-link", "Подтверждение регистрации", Set.of("token")),
    REGISTRATION_CODE("mail/registration-code", "Код подтверждения", Set.of("code")),
    PASSWORD_RESET("mail/password-reset", "Сброс пароля", Set.of("code")),
    ADMIN_REGISTRATION("mail/admin-registration", "Новая регистрация: {email}", Set.of("email", "userId")),
    TICKET_CREATED("mail/ticket-created", "Создана новая заявка #{ticketId}",
            Set.of("ticketId", "portalId")),
    TICKET_REPLY("mail/ticket-reply", "[Заявка#{ticketId}] Новое сообщение",
            Set.of("ticketId", "portalId")),
    TICKET_STATUS("mail/ticket-status", "Задан статус:{status} для заявки#{ticketId}",
            Set.of("ticketId", "portalId", "status")),
    TICKET_PRIORITY("mail/ticket-priority", "Задан приоритет:{priority} для заявки#{ticketId}",
            Set.of("ticketId", "portalId", "priority")),
    TICKET_DELETED("mail/ticket-deleted", "Удалена заявка#{ticketId}",
            Set.of("ticketId", "deletedByEmail")),
    PORTAL_CREATED("mail/portal-created", "Создан новый портал #{portalId}", Set.of("portalId")),
    PORTAL_ADD_USER("mail/portal-add-user", "Вас добавили к порталу #{portalId}", Set.of("portalId")),
    PORTAL_DELETED("mail/portal-deleted", "Удален портал#{portalId}",
            Set.of("portalId", "deletedByEmail")),
    PORTAL_TRANSFER_REQUESTED("mail/portal-transfer-requested", "Передача портала #{portalId}",
            Set.of("portalId", "initiatorName", "reason")),
    PORTAL_TRANSFER_PROPOSED("mail/portal-transfer-proposed", "Вам передают портал #{portalId}",
            Set.of("portalId", "initiatorName", "reason")),
    PORTAL_TRANSFER_ACCEPTED("mail/portal-transfer-accepted", "Портал #{portalId} сменил владельца",
            Set.of("portalId", "newOwnerName", "reason")),
    PORTAL_TRANSFER_REJECTED("mail/portal-transfer-rejected", "Запрос на передачу портала #{portalId} отклонён",
            Set.of("portalId", "proposedName", "reason")),
    PORTAL_TRANSFER_CANCELLED("mail/portal-transfer-cancelled", "Передача портала #{portalId} отменена",
            Set.of("portalId", "initiatorName")),
    PORTAL_TRANSFER_EXPIRED("mail/portal-transfer-expired", "Истёк срок передачи портала #{portalId}",
            Set.of("portalId")),
    NOTIFICATION_REPEAT("mail/notification-repeat", "[Напоминание] {title}",
            Set.of("title", "ticketId", "portalId"));

    /** Предел длины темы письма: длинные заголовки уведомлений не должны ломать заголовок в клиенте. */
    static final int SUBJECT_MAX_LENGTH = 78;

    private final String templatePath;
    private final String subjectTemplate;
    private final Set<String> requiredModelKeys;

    EmailTemplateId(String templatePath, String subjectTemplate, Set<String> requiredModelKeys) {
        this.templatePath = templatePath;
        this.subjectTemplate = subjectTemplate;
        this.requiredModelKeys = requiredModelKeys;
    }

    /** Путь к шаблону без расширения — Thymeleaf добавляет suffix сам. */
    public String templatePath() {
        return templatePath;
    }

    public String subjectTemplate() {
        return subjectTemplate;
    }

    /** Ключи модели, без которых тема собрана не будет — контракт для вызывающих сервисов и тестов. */
    public Set<String> requiredModelKeys() {
        return requiredModelKeys;
    }

    /**
     * Тема письма: плейсхолдеры {@code {key}} заменяются значениями модели, сверх {@value #SUBJECT_MAX_LENGTH} символов
     * тема обрезается с многоточием.
     */
    public String resolveSubject(Map<String, Object> model) {
        String subject = subjectTemplate;
        for (Map.Entry<String, Object> entry : model.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (subject.contains(placeholder)) {
                subject = subject.replace(placeholder, String.valueOf(entry.getValue()));
            }
        }
        if (subject.length() > SUBJECT_MAX_LENGTH) {
            subject = subject.substring(0, SUBJECT_MAX_LENGTH - 1) + "\u2026";
        }
        return subject;
    }
}

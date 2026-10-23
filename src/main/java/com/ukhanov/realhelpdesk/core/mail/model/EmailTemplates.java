package com.ukhanov.realhelpdesk.core.mail.model;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Component
public class EmailTemplates {

    private final MessageSource messageSource;
    private final EmailProperties emailProperties;

    public String registrationLinkSubject() {
        return text("email.registration.link.subject");
    }

    public String registrationLinkBody(String token) {
        return text("email.registration.link.body", projectName(), domain(), token);
    }

    public String registrationCodeSubject() {
        return text("email.registration.code.subject");
    }

    public String registrationCodeBody(String code) {
        return text("email.registration.code.body", projectName(), domain(), code);
    }

    public String passwordResetSubject() {
        return text("email.password.reset.subject");
    }

    public String passwordResetBody(String code) {
        return text("email.password.reset.body", projectName(), domain(), code);
    }

    public String ticketReplySubject(Long ticketId) {
        return text("email.ticket.reply.subject", String.valueOf(ticketId));
    }

    public String ticketReplyBody(Long ticketId, Long portalId) {
        return text("email.ticket.reply.body", String.valueOf(ticketId), domain(), String.valueOf(portalId),
                projectName());
    }

    public String ticketCreatedSubject(Long ticketId) {
        return text("email.ticket.created.subject", String.valueOf(ticketId));
    }

    public String ticketCreatedBody(Long ticketId, Long portalId) {
        return text("email.ticket.created.body", String.valueOf(ticketId), domain(), String.valueOf(portalId),
                projectName());
    }

    public String portalCreatedSubject(Long portalId) {
        return text("email.portal.created.subject", String.valueOf(portalId));
    }

    public String portalCreatedBody(Long portalId) {
        return text("email.portal.created.body", String.valueOf(portalId), domain(), projectName());
    }

    public String portalAddUserSubject(Long portalId) {
        return text("email.portal.add.user.subject", String.valueOf(portalId));
    }

    public String portalAddUserBody(Long portalId) {
        return text("email.portal.add.user.body", domain(), projectName());
    }

    public String updateStatusTicketSubject(Long ticketId, TicketStatus ticketStatus) {
        return text("email.ticket.status.subject", String.valueOf(ticketStatus), String.valueOf(ticketId));
    }

    public String updateStatusTicketBody(Long ticketId, Long portalId) {
        return text("email.ticket.status.body", domain(), String.valueOf(portalId), String.valueOf(ticketId),
                projectName());
    }

    public String updatePriorityTicketSubject(Long ticketId, TicketPriority ticketPriority) {
        return text("email.ticket.priority.subject", String.valueOf(ticketPriority), String.valueOf(ticketId));
    }

    public String updatePriorityTicketBody(Long ticketId, Long portalId) {
        return text("email.ticket.priority.body", domain(), String.valueOf(portalId), String.valueOf(ticketId),
                projectName());
    }

    public String deletedTicketSubject(Long ticketId) {
        return text("email.ticket.deleted.subject", String.valueOf(ticketId));
    }

    public String deletedTicketBody(Long ticketId, String email) {
        return text("email.ticket.deleted.body", String.valueOf(ticketId), email, projectName());
    }

    public String deletedPortalSubject(Long portalId) {
        return text("email.portal.deleted.subject", String.valueOf(portalId));
    }

    public String deletedPortalBody(Long portalId, String email) {
        return text("email.portal.deleted.body", String.valueOf(portalId), email, projectName());
    }

    public String portalTransferRequestedSubject(Long portalId) {
        return text("email.portal.transfer.requested.subject", String.valueOf(portalId));
    }

    public String portalTransferRequestedBody(Long portalId, String initiatorName, String reason) {
        return text("email.portal.transfer.requested.body", String.valueOf(portalId), initiatorName, reason,
                projectName());
    }

    public String portalTransferProposedSubject(Long portalId) {
        return text("email.portal.transfer.proposed.subject", String.valueOf(portalId));
    }

    public String portalTransferProposedBody(Long portalId, String initiatorName, String reason) {
        return text("email.portal.transfer.proposed.body", String.valueOf(portalId), initiatorName, reason, domain(),
                projectName());
    }

    public String portalTransferAcceptedSubject(Long portalId) {
        return text("email.portal.transfer.accepted.subject", String.valueOf(portalId));
    }

    public String portalTransferAcceptedBody(Long portalId, String newOwnerName, String reason) {
        return text("email.portal.transfer.accepted.body", String.valueOf(portalId), newOwnerName, reason,
                projectName());
    }

    public String portalTransferRejectedSubject(Long portalId) {
        return text("email.portal.transfer.rejected.subject", String.valueOf(portalId));
    }

    public String portalTransferRejectedBody(Long portalId, String proposedName, String reason) {
        return text("email.portal.transfer.rejected.body", String.valueOf(portalId), proposedName, reason,
                projectName());
    }

    public String portalTransferCancelledSubject(Long portalId) {
        return text("email.portal.transfer.cancelled.subject", String.valueOf(portalId));
    }

    public String portalTransferCancelledBody(Long portalId, String initiatorName) {
        return text("email.portal.transfer.cancelled.body", String.valueOf(portalId), initiatorName, projectName());
    }

    public String portalTransferExpiredSubject(Long portalId) {
        return text("email.portal.transfer.expired.subject", String.valueOf(portalId));
    }

    public String portalTransferExpiredBody(Long portalId) {
        return text("email.portal.transfer.expired.body", String.valueOf(portalId), projectName());
    }

    // Заголовок делает письмо различимым в клиенте без открытия тела
    public String notificationRepeatSubject(String title) {
        return text("email.notification.repeat.subject", title);
    }

    public String notificationRepeatBody(String title, Long ticketId, Long portalId) {
        return text("email.notification.repeat.body", title, domain(), String.valueOf(portalId),
                String.valueOf(ticketId), projectName());
    }

    private String text(String code, Object... args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    private String domain() {
        return emailProperties.getDomain();
    }

    private String projectName() {
        return emailProperties.getProjectName();
    }
}

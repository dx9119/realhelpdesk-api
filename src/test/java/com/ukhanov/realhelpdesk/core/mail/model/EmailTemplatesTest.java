package com.ukhanov.realhelpdesk.core.mail.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Шаблоны писем: EmailTemplates")
class EmailTemplatesTest {

    private static final Long TICKET_ID = 77L;
    private static final Long PORTAL_ID = 5L;

    private EmailTemplates emailTemplates;

    @BeforeEach
    void setUp() {
        emailTemplates = EmailTemplatesFixture.emailTemplates();
    }

    @Test
    @DisplayName("Подтверждение регистрации: тема и код в теле письма")
    void registrationLink_code_returnsSubjectAndBodyWithCode() {

        // given
        String code = "123456";

        // when
        String subject = emailTemplates.registrationLinkSubject();
        String body = emailTemplates.registrationLinkBody(code);

        // then
        assertThat(subject).isEqualTo("Подтверждение регистрации");
        assertThat(body).contains(code)
                .contains("https://" + EmailTemplatesFixture.DOMAIN + "/notify-settings")
                .contains(EmailTemplatesFixture.PROJECT_NAME);
    }

    @Test
    @DisplayName("Код подтверждения: тема и код в теле письма")
    void registrationCode_code_returnsSubjectAndBodyWithCode() {

        // given
        String code = "654321";

        // when
        String subject = emailTemplates.registrationCodeSubject();
        String body = emailTemplates.registrationCodeBody(code);

        // then
        assertThat(subject).isEqualTo("Код подтверждения");
        assertThat(body).contains(code).contains("https://" + EmailTemplatesFixture.DOMAIN + "/notify-settings");
    }

    @Test
    @DisplayName("Сброс пароля: ссылка на форму со сбросом содержит код")
    void passwordReset_code_returnsSubjectAndBodyWithResetLink() {

        // given
        String code = "999888";

        // when
        String subject = emailTemplates.passwordResetSubject();
        String body = emailTemplates.passwordResetBody(code);

        // then
        assertThat(subject).isEqualTo("Сброс пароля");
        assertThat(body).contains("https://" + EmailTemplatesFixture.DOMAIN + "/pass-reset?code=" + code)
                .contains("проигнорируйте это письмо");
    }

    @Test
    @DisplayName("Новое сообщение в заявке: тема и ссылка на заявку")
    void ticketReply_knownTicket_returnsSubjectAndLink() {

        // given
        // when
        String expectedLink = ticketLink();

        String subject = emailTemplates.ticketReplySubject(TICKET_ID);
        String body = emailTemplates.ticketReplyBody(TICKET_ID, PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("[Заявка#77] Новое сообщение");
        assertThat(body).contains("В заявке #77 появилось новое сообщение").contains(expectedLink);
    }

    @Test
    @DisplayName("Созданная заявка: тема и ссылка на заявку")
    void ticketCreated_knownTicket_returnsSubjectAndLink() {

        // given
        // when
        String expectedLink = ticketLink();

        String subject = emailTemplates.ticketCreatedSubject(TICKET_ID);
        String body = emailTemplates.ticketCreatedBody(TICKET_ID, PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("Создана новая заявка #77");
        assertThat(body).contains("Создана заявка #77").contains(expectedLink);
    }

    @Test
    @DisplayName("Ссылки на заявку одинаковы во всех шаблонах: /portal/{portalId}/ticket/{ticketId}")
    void ticketBodies_knownTicket_shareSameLink() {

        // given
        // when
        String expectedLink = ticketLink();

        String created = emailTemplates.ticketCreatedBody(TICKET_ID, PORTAL_ID);
        String reply = emailTemplates.ticketReplyBody(TICKET_ID, PORTAL_ID);
        String status = emailTemplates.updateStatusTicketBody(TICKET_ID, PORTAL_ID);
        String priority = emailTemplates.updatePriorityTicketBody(TICKET_ID, PORTAL_ID);

        // then
        assertThat(created).contains(expectedLink);
        assertThat(reply).contains(expectedLink);
        assertThat(status).contains(expectedLink);
        assertThat(priority).contains(expectedLink);
    }

    @Test
    @DisplayName("Созданный портал: тема и ссылка на список порталов")
    void portalCreated_knownPortal_returnsSubjectAndUrl() {

        // given
        // when
        String subject = emailTemplates.portalCreatedSubject(PORTAL_ID);
        String body = emailTemplates.portalCreatedBody(PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("Создан новый портал #5");
        assertThat(body).contains("Создан портал #5")
                .contains("https://" + EmailTemplatesFixture.DOMAIN + "/portal-manager");
    }

    @Test
    @DisplayName("Добавленный пользователь портала: тема и ссылка на общий список порталов")
    void portalAddUser_knownPortal_returnsSubjectAndUrl() {

        // given
        // when
        String subject = emailTemplates.portalAddUserSubject(PORTAL_ID);
        String body = emailTemplates.portalAddUserBody(PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("Вас добавили к порталу #5");
        assertThat(body).contains("Общие порталы")
                .contains("https://" + EmailTemplatesFixture.DOMAIN + "/portal-manager");
    }

    @Test
    @DisplayName("Смена статуса заявки: тема со статусом, ссылка ведёт на портал и заявку")
    void updateStatusTicket_closedStatus_returnsSubjectAndLink() {

        // given
        // when
        String subject = emailTemplates.updateStatusTicketSubject(TICKET_ID, TicketStatus.CLOSED);
        String body = emailTemplates.updateStatusTicketBody(TICKET_ID, PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("Задан статус:CLOSED для заявки#77");
        assertThat(body).contains(ticketLink());
    }

    @Test
    @DisplayName("Смена приоритета заявки: тема с приоритетом, ссылка ведёт на портал и заявку")
    void updatePriorityTicket_criticalPriority_returnsSubjectAndLink() {

        // given
        // when
        String subject = emailTemplates.updatePriorityTicketSubject(TICKET_ID, TicketPriority.CRITICAL);
        String body = emailTemplates.updatePriorityTicketBody(TICKET_ID, PORTAL_ID);

        // then
        assertThat(subject).isEqualTo("Задан приоритет:CRITICAL для заявки#77");
        assertThat(body).contains(ticketLink());
    }

    @Test
    @DisplayName("Удалённая заявка: тема и инициатор удаления в теле")
    void deletedTicket_knownActor_returnsSubjectAndActor() {

        // given
        String actor = "user@example.com";

        // when
        String subject = emailTemplates.deletedTicketSubject(TICKET_ID);
        String body = emailTemplates.deletedTicketBody(TICKET_ID, actor);

        // then
        assertThat(subject).isEqualTo("Удалена заявка#77");
        assertThat(body).contains("Заявка #77 была удалена пользователем " + actor);
    }

    @Test
    @DisplayName("Удалённый портал: тема и инициатор удаления в теле")
    void deletedPortal_knownActor_returnsSubjectAndActor() {

        // given
        String actor = "user@example.com";

        // when
        String subject = emailTemplates.deletedPortalSubject(PORTAL_ID);
        String body = emailTemplates.deletedPortalBody(PORTAL_ID, actor);

        // then
        assertThat(subject).isEqualTo("Удален портал#5");
        assertThat(body).contains("Портал #5 был удален пользователем " + actor);
    }

    @Test
    @DisplayName("Крупные ID не группируются пробелами при подстановке в шаблон")
    void ticketCreated_largeIds_areNotGroupedByFormat() {

        // given
        // when
        String subject = emailTemplates.ticketCreatedSubject(12345L);
        String body = emailTemplates.ticketCreatedBody(12345L, 67890L);

        // then
        assertThat(subject).isEqualTo("Создана новая заявка #12345");
        assertThat(body).contains("https://" + EmailTemplatesFixture.DOMAIN + "/ticket-show/portal/67890/ticket/12345");
    }

    @Test
    @DisplayName("Письмо новому владельцу: тема, ссылка /portal-transfer и срок действия")
    void portalTransferProposed_knownReason_returnsLinkAndDeadline() {

        // given
        String ownerName = "Иванов Иван";
        String reason = "Причина передачи";

        // when
        String subject = emailTemplates.portalTransferProposedSubject(PORTAL_ID);
        String body = emailTemplates.portalTransferProposedBody(PORTAL_ID, ownerName, reason);

        // then
        assertThat(subject).isEqualTo("Вам передают портал #5");
        assertThat(body).contains(ownerName + " предлагает передать вам владение порталом #5")
                .contains(reason)
                .contains("https://" + EmailTemplatesFixture.DOMAIN + "/portal-transfer/5")
                .contains("72 часа");
    }

    @Test
    @DisplayName("Письма о решении по передаче: новому владельцу и причине отклонения")
    void transferDecisionBodies_knownNamesAndReasons_containThem() {

        // given
        String ownerName = "Иванов Иван";
        String reason = "Причина передачи";
        String rejectionReason = "Не время";

        // when
        String acceptedSubject = emailTemplates.portalTransferAcceptedSubject(PORTAL_ID);
        String acceptedBody = emailTemplates.portalTransferAcceptedBody(PORTAL_ID, ownerName, reason);
        String rejectedSubject = emailTemplates.portalTransferRejectedSubject(PORTAL_ID);
        String rejectedBody = emailTemplates.portalTransferRejectedBody(PORTAL_ID, ownerName, rejectionReason);
        String cancelledBody = emailTemplates.portalTransferCancelledBody(PORTAL_ID, ownerName);
        String expiredBody = emailTemplates.portalTransferExpiredBody(PORTAL_ID);

        // then
        assertThat(acceptedSubject).isEqualTo("Портал #5 сменил владельца");
        assertThat(acceptedBody).contains("Владелец портала #5 теперь — " + ownerName).contains(reason);
        assertThat(rejectedSubject).isEqualTo("Запрос на передачу портала #5 отклонён");
        assertThat(rejectedBody).contains(ownerName + " отклонил передачу вам портала #5").contains(rejectionReason);
        assertThat(cancelledBody).contains(ownerName + " отозвал предложение");
        assertThat(expiredBody).contains("истёк");
    }

    private String ticketLink() {
        return "https://" + EmailTemplatesFixture.DOMAIN + "/ticket-show/portal/" + PORTAL_ID + "/ticket/" + TICKET_ID;
    }
}

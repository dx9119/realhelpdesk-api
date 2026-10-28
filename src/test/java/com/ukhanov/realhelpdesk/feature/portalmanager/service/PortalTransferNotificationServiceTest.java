package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferRequestModel;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Email-уведомления о передаче портала: PortalTransferNotificationService")
class PortalTransferNotificationServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long INITIATOR_ID = 10L;
    private static final Long PROPOSED_ID = 20L;
    private static final Long NEW_OWNER_ID = 20L;
    private static final Long PORTAL_ID = 100L;

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INITIATOR_EMAIL = "initiator@example.com";
    private static final String PROPOSED_EMAIL = "proposed@example.com";
    private static final String PORTAL_NAME = "Портал";
    private static final String REASON = "Причина передачи";

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private UserDomainService userDomainService;

    private PortalTransferNotificationService service;

    private PortalModel portal;
    private PortalTransferRequestModel transfer;
    private UserModel owner;
    private UserModel initiator;
    private UserModel proposed;

    @Captor
    private ArgumentCaptor<Map<String, Object>> modelCaptor;

    @Captor
    private ArgumentCaptor<Set<Long>> recipientsCaptor;

    @BeforeEach
    void setUp() {
        service = new PortalTransferNotificationService(notificationPublisher, emailDeliveryService,
                userDomainService);

        owner = user(OWNER_ID, OWNER_EMAIL, "Иванов", "Иван");
        initiator = user(INITIATOR_ID, INITIATOR_EMAIL, "Петров", "Пётр");
        proposed = user(PROPOSED_ID, PROPOSED_EMAIL, "Сидоров", "Сидор");

        portal = new PortalModel();
        portal.setId(PORTAL_ID);
        portal.setName(PORTAL_NAME);
        portal.setOwner(owner);
        portal.setAllowedUserIds(new java.util.HashSet<>(Set.of(INITIATOR_ID)));

        transfer = new PortalTransferRequestModel();
        transfer.setPortalId(PORTAL_ID);
        transfer.setInitiatorId(INITIATOR_ID);
        transfer.setProposedOwnerId(PROPOSED_ID);
        transfer.setReasonInitiator(REASON);
        transfer.setReasonProposed("Принято");
    }

    // ────────────────────────────────────────────────
    // notifyTransferRequested
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Запрос передачи: участникам PORTAL_TRANSFER_REQUESTED, кандидату PORTAL_TRANSFER_PROPOSED")
    void notifyTransferRequested_participantsAndProposed_sendsBothTemplates() throws Exception {

        // given / when
        service.notifyTransferRequested(portal, transfer, initiator, proposed);

        // then — участникам (owner + initiator, без proposed)
        verify(emailDeliveryService).sendPortalUsersNotification(recipientsCaptor.capture(),
                eq(EmailTemplateId.PORTAL_TRANSFER_REQUESTED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_REQUESTED));
        assertThat(recipientsCaptor.getValue()).containsExactlyInAnyOrder(OWNER_ID, INITIATOR_ID);
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("initiatorName", "Петров Пётр")
                .containsEntry("reason", REASON);

        // then — кандидату
        verify(emailDeliveryService).sendUserNotification(eq(PROPOSED_EMAIL),
                eq(EmailTemplateId.PORTAL_TRANSFER_PROPOSED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_REQUESTED), eq(PROPOSED_ID));
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("initiatorName", "Петров Пётр")
                .containsEntry("reason", REASON);
    }

    @Test
    @DisplayName("Запрос передачи без участников: кандидату письмо уходит, участникам — нет")
    void notifyTransferRequested_noParticipants_sendsOnlyProposed() throws Exception {

        // given
        portal.setAllowedUserIds(null);
        portal.setOwner(proposed); // единственный участник — сам кандидат, он же исключается из participants

        // when
        service.notifyTransferRequested(portal, transfer, initiator, proposed);

        // then
        verify(emailDeliveryService, never()).sendPortalUsersNotification(anySet(), any(), any(), any());
        verify(emailDeliveryService).sendUserNotification(eq(PROPOSED_EMAIL),
                eq(EmailTemplateId.PORTAL_TRANSFER_PROPOSED), any(), eq(NotificationEvent.PORTAL_TRANSFER_REQUESTED),
                eq(PROPOSED_ID));
    }

    // ────────────────────────────────────────────────
    // notifyTransferAccepted
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Подтверждение передачи: PORTAL_TRANSFER_ACCEPTED старому владельцу и допущенным")
    void notifyTransferAccepted_recipientsWithOldOwner_sendsAcceptedTemplate() throws Exception {

        // given
        UserModel newOwner = proposed;
        Set<Long> newAllowed = Set.of(INITIATOR_ID);

        // when
        service.notifyTransferAccepted(portal, transfer, owner, newOwner, newAllowed);

        // then — recipients = newAllowed + oldOwner, минус newOwner
        verify(emailDeliveryService).sendPortalUsersNotification(recipientsCaptor.capture(),
                eq(EmailTemplateId.PORTAL_TRANSFER_ACCEPTED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_ACCEPTED));
        assertThat(recipientsCaptor.getValue()).containsExactlyInAnyOrder(OWNER_ID, INITIATOR_ID);
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("newOwnerName", "Сидоров Сидор")
                .containsEntry("reason", REASON);
    }

    // ────────────────────────────────────────────────
    // notifyTransferRejected
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Отклонение: PORTAL_TRANSFER_REJECTED инициатору")
    void notifyTransferRejected_knownInitiator_sendsRejectedTemplate() throws Exception {

        // given / when
        service.notifyTransferRejected(portal, transfer, initiator, proposed);

        // then
        verify(emailDeliveryService).sendUserNotification(eq(INITIATOR_EMAIL),
                eq(EmailTemplateId.PORTAL_TRANSFER_REJECTED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_REJECTED), eq(INITIATOR_ID));
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("proposedName", "Сидоров Сидор")
                .containsEntry("reason", "Принято");
    }

    @Test
    @DisplayName("Отклонение без known инициатора: письма нет, in-app оповещение — есть")
    void notifyTransferRejected_nullInitiator_noEmail() throws Exception {

        // given / when
        service.notifyTransferRejected(portal, transfer, null, proposed);

        // then
        verify(emailDeliveryService, never()).sendUserNotification(any(), any(), any(), any(), any());
        verify(notificationPublisher).publishToUsers(any());
    }

    // ────────────────────────────────────────────────
    // notifyTransferCancelled
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Отзыв: PORTAL_TRANSFER_CANCELLED кандидату")
    void notifyTransferCancelled_knownProposed_sendsCancelledTemplate() throws Exception {

        // given
        when(userDomainService.getUserById(PROPOSED_ID)).thenReturn(proposed);

        // when
        service.notifyTransferCancelled(portal, transfer, owner);

        // then
        verify(emailDeliveryService).sendUserNotification(eq(PROPOSED_EMAIL),
                eq(EmailTemplateId.PORTAL_TRANSFER_CANCELLED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_CANCELLED), eq(PROPOSED_ID));
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("initiatorName", "Иванов Иван");
    }

    @Test
    @DisplayName("Отзыв, кандидат удалён из БД: письма нет, исключение не всплывает")
    void notifyTransferCancelled_proposedDeleted_noEmail() throws Exception {

        // given
        when(userDomainService.getUserById(PROPOSED_ID))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("нет"));

        // when / then — не бросает
        assertThatCode(() -> service.notifyTransferCancelled(portal, transfer, owner)).doesNotThrowAnyException();

        verify(emailDeliveryService, never()).sendUserNotification(any(), any(), any(), any(), any());
    }

    // ────────────────────────────────────────────────
    // notifyTransferExpired
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Истечение срока: PORTAL_TRANSFER_EXPIRED инициатору")
    void notifyTransferExpired_initiator_sendsExpiredTemplate() throws Exception {

        // given / when
        service.notifyTransferExpired(portal, transfer, initiator);

        // then
        verify(emailDeliveryService).sendUserNotification(eq(INITIATOR_EMAIL),
                eq(EmailTemplateId.PORTAL_TRANSFER_EXPIRED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_TRANSFER_EXPIRED), eq(INITIATOR_ID));
        assertThat(modelCaptor.getValue()).containsEntry("portalId", PORTAL_ID);
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private UserModel user(Long id, String email, String lastName, String firstName) {
        UserModel user = new UserModel();
        user.setId(id);
        user.setEmail(email);
        user.setLastName(lastName);
        user.setFirstName(firstName);
        return user;
    }
}

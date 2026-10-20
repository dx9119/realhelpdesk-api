package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;
import com.ukhanov.realhelpdesk.domain.notification.repository.NotificationRepository;
import com.ukhanov.realhelpdesk.domain.notification.repository.UserNotificationPreferencesRepository;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Фан-аут in-app оповещений (NotificationPublisher)")
class NotificationPublisherTest {

    private static final Long ACTOR_ID = 1L;
    private static final Long OWNER_ID = 2L;
    private static final Long SHARED_ID = 3L;
    private static final Long TICKET_ID = 77L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserNotificationPreferencesRepository preferencesRepository;

    @Mock
    private NotificationWaitRegistry waitRegistry;

    private NotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new NotificationPublisher(notificationRepository, preferencesRepository, waitRegistry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Портал: получатели owner + allowed, автор действия исключён, будит ожидающих")
    void publishToPortalUsers_actorIsOwner_skipsActorWakesMembers() {

        // given
        // when
        PortalModel portal = portal(OWNER_ID, SHARED_ID);

        publisher.publishToPortalUsers(portal, NotificationEvent.NEW_TICKET, OWNER_ID, TICKET_ID, "Проблема с оплатой");

        ArgumentCaptor<NotificationModel> captor = ArgumentCaptor.forClass(NotificationModel.class);
        // then
        verify(notificationRepository).save(captor.capture());
        NotificationModel saved = captor.getValue();
        assertThat(saved.getRecipientId()).isEqualTo(SHARED_ID);
        assertThat(saved.getEvent()).isEqualTo(NotificationEvent.NEW_TICKET);
        assertThat(saved.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(saved.getPortalId()).isNull();
        assertThat(saved.getTitle()).isEqualTo("Проблема с оплатой");
        assertThat(saved.isRead()).isFalse();

        verify(waitRegistry).wake(SHARED_ID);
        verify(waitRegistry, never()).wake(OWNER_ID);
    }

    @Test
    @DisplayName("Все получатели — авторы действий: ничего не сохраняется и не будится")
    void publishToPortalUsers_onlyActor_publishesNothing() {

        // given
        // when
        PortalModel portal = portal(ACTOR_ID);

        publisher.publishToPortalUsers(portal, NotificationEvent.NEW_TICKET, ACTOR_ID, TICKET_ID, "Заявка");

        // then
        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(waitRegistry, never()).wake(any(Long.class));
        verify(preferencesRepository, never()).findByUserIdIn(anySet());
    }

    @Test
    @DisplayName("Событие, отключённое в настройках пользователя, не сохраняется; пользователь без настроек получает")
    void publishToUsers_eventDisabledByPrefs_skipsOnlyThatUser() {

        // given
        // when
        UserNotificationPreferencesModel preferences = new UserNotificationPreferencesModel(SHOULD_NOT,
                Set.of(NotificationEvent.NEW_MESSAGE));
        when(preferencesRepository.findByUserIdIn(anySet())).thenReturn(List.of(preferences));

        publisher.publishToUsers(Set.of(SHOULD_NOT, ANYTHING_OK), NotificationEvent.NEW_TICKET, null, TICKET_ID, null, "Заголовок");

        ArgumentCaptor<NotificationModel> captor = ArgumentCaptor.forClass(NotificationModel.class);
        // then
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo(ANYTHING_OK);
        verify(waitRegistry).wake(ANYTHING_OK);
        verify(waitRegistry, never()).wake(SHOULD_NOT);
    }

    @Test
    @DisplayName("Событие вне каталога in-app не публикуется")
    void publishToUsers_unsupportedEvent_skipped() {

        // given
        // when
        publisher.publishToUsers(Set.of(SHARED_ID), NotificationEvent.RECOVERY_PASSWORD, null, null, null, null);

        // then
        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(preferencesRepository, never()).findByUserIdIn(anySet());
        verify(waitRegistry, never()).wake(any(Long.class));
    }

    @Test
    @DisplayName("Внутренняя ошибка не выходит наружу — публикация не валит основную операцию")
    void publishToUsers_repositoryFailure_isSwallowed() {

        // given
        // when
        when(notificationRepository.save(any(NotificationModel.class))).thenThrow(new IllegalStateException("БД недоступна"));

        // then
        assertThatCode(() -> publisher.publishToUsers(Set.of(SHARED_ID), NotificationEvent.NEW_TICKET, null, TICKET_ID, null, "Т"))
                .doesNotThrowAnyException();
        verify(waitRegistry, never()).wake(any(Long.class));
    }

    @Test
    @DisplayName("При активной транзакции ожидающих будят только после коммита")
    void publishToUsers_activeTransaction_wakesAfterCommit() {

        // given
        // when
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishToUsers(Set.of(SHARED_ID), NotificationEvent.NEW_TICKET, null, TICKET_ID, null, "Заявка");

        // then
        verify(waitRegistry, never()).wake(any(Long.class));

        List<TransactionSynchronization> synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(waitRegistry).wake(SHARED_ID);
    }

    @Test
    @DisplayName("Длинный заголовок обрезается до 255 символов")
    void publishToUsers_longTitle_truncated() {

        // given
        // when
        String longTitle = "ы".repeat(300);

        publisher.publishToUsers(Set.of(SHARED_ID), NotificationEvent.NEW_TICKET, null, TICKET_ID, null, longTitle);

        ArgumentCaptor<NotificationModel> captor = ArgumentCaptor.forClass(NotificationModel.class);
        // then
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(255);
    }

    @Test
    @DisplayName("Пустой список получателей — ничего не делаем")
    void publishToUsers_emptyRecipients_noop() {

        // given
        // when
        publisher.publishToUsers(Set.of(), NotificationEvent.NEW_TICKET, null, TICKET_ID, null, "З");

        // then
        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(preferencesRepository, never()).findByUserIdIn(anySet());
    }

    private static final Long SHOULD_NOT = 10L;
    private static final Long ANYTHING_OK = 11L;

    private PortalModel portal(Long... userIds) {
        PortalModel portal = new PortalModel();
        UserModel owner = new UserModel();
        owner.setId(userIds[0]);
        portal.setOwner(owner);
        if (userIds.length > 1) {
            Set<Long> allowed = new HashSet<>();
            for (int i = 1; i < userIds.length; i++) {
                allowed.add(userIds[i]);
            }
            portal.setAllowedUserIds(allowed);
        }
        return portal;
    }
}

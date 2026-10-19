package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;
import com.ukhanov.realhelpdesk.domain.notification.repository.NotificationRepository;
import com.ukhanov.realhelpdesk.domain.notification.repository.UserNotificationPreferencesRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Повторы непрочитанных оповещений (NotificationRepeatService)")
class NotificationRepeatServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long USER_2_ID = 43L;
    private static final Long SOURCE_ID = 77L;
    private static final Long SOURCE_GROUP_ID = 50L;
    private static final Long TICKET_ID = 11L;
    private static final Long PORTAL_ID = 2L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserNotificationPreferencesRepository preferencesRepository;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private UserDomainService userDomainService;

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private EmailTemplates emailTemplates;

    private NotificationRepeatService service;

    @BeforeEach
    void setUp() {
        service = new NotificationRepeatService(notificationRepository, preferencesRepository, notificationPublisher, userDomainService,
                emailDeliveryService, emailTemplates);
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of());
        // как в JPA: save возвращает ту же строку с присвоенным id
        when(notificationRepository.save(any(NotificationModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Кандидатов нет — ни сохранений, ни пробуждения")
    void noCandidates_noop() throws Exception {
        service.repeatDueNotifications();

        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(notificationPublisher, never()).wakeAfterCommit(any());
        verify(emailDeliveryService, never()).sendUserNotification(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Без строки настроек: источник старше 30 минут → напоминание с группой источника, ожидающих будят")
    void dueWithoutPreferences_createsReminderAndWakes() {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));

        service.repeatDueNotifications();

        ArgumentCaptor<NotificationModel> captor = ArgumentCaptor.forClass(NotificationModel.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        NotificationModel reminder = captor.getValue();
        assertThat(reminder.getRecipientId()).isEqualTo(USER_ID);
        assertThat(reminder.getEvent()).isEqualTo(NotificationEvent.NEW_TICKET);
        assertThat(reminder.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(reminder.getTitle()).isEqualTo("Проблема");
        assertThat(reminder.getGroupId()).isEqualTo(SOURCE_ID);
        assertThat(reminder.isRead()).isFalse();
        verify(notificationPublisher).wakeAfterCommit(List.of(USER_ID));
    }

    @Test
    @DisplayName("Напоминание наследует group_id, если источник уже копия")
    void reminderInheritsSourceGroup() throws Exception {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        source.setId(SOURCE_GROUP_ID);
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));

        service.repeatDueNotifications();

        ArgumentCaptor<NotificationModel> captor = ArgumentCaptor.forClass(NotificationModel.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getGroupId()).isEqualTo(SOURCE_GROUP_ID);
    }

    @Test
    @DisplayName("Интервал не истёк — напоминания нет")
    void notYetDue_noReminder() {
        NotificationModel source = source(Instant.now().minusSeconds(5 * 60));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));

        service.repeatDueNotifications();

        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(notificationPublisher, never()).wakeAfterCommit(any());
    }

    @Test
    @DisplayName("Повтор выключен в настройках — напоминания нет")
    void repeatDisabled_noReminder() {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        UserNotificationPreferencesModel preferences = preferences(true, 30, Set.of(NotificationEvent.NEW_TICKET));
        preferences.setRepeatEnabled(false);
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));
        when(preferencesRepository.findByUserIdIn(anySet())).thenReturn(List.of(preferences));

        service.repeatDueNotifications();

        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(notificationPublisher, never()).wakeAfterCommit(any());
    }

    @Test
    @DisplayName("Событие выключено в настройках — напоминания нет")
    void eventDisabled_noReminder() {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        UserNotificationPreferencesModel preferences = preferences(true, 30, Set.of(NotificationEvent.CHANGE_TICKET));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));
        when(preferencesRepository.findByUserIdIn(anySet())).thenReturn(List.of(preferences));

        service.repeatDueNotifications();

        verify(notificationRepository, never()).save(any(NotificationModel.class));
        verify(notificationPublisher, never()).wakeAfterCommit(any());
    }

    @Test
    @DisplayName("Пользовательский интервал учитывается вместо 30 минут по умолчанию")
    void customInterval_respected() {
        NotificationModel source = source(Instant.now().minusSeconds(2 * 60));
        UserNotificationPreferencesModel everyMinute = preferences(true, 1, Set.of(NotificationEvent.NEW_TICKET));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));
        when(preferencesRepository.findByUserIdIn(anySet())).thenReturn(List.of(everyMinute));

        service.repeatDueNotifications();

        verify(notificationRepository, times(1)).save(any(NotificationModel.class));
        verify(notificationPublisher).wakeAfterCommit(List.of(USER_ID));
    }

    @Test
    @DisplayName("Повтор: создаёт in-app напоминание и отправляет письмо-напоминание")
    void repeat_sendsInAppReminderAndEmail() throws Exception {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));
        UserModel recipient = new UserModel();
        recipient.setId(USER_ID);
        recipient.setEmail("user@example.com");
        when(userDomainService.getUsersByIds(anySet())).thenReturn(Map.of(USER_ID, recipient));

        service.repeatDueNotifications();

        verify(notificationRepository, times(1)).save(any(NotificationModel.class));
        verify(notificationPublisher).wakeAfterCommit(List.of(USER_ID));
        verify(emailDeliveryService, times(1)).sendUserNotification(eq("user@example.com"), any(), any(), eq(NotificationEvent.NEW_TICKET),
                eq(USER_ID));
    }

    @Test
    @DisplayName("Повтор: без ticketId/portalId — email не отправляется, in-app — да")
    void repeat_noTicketId_emailSkipped() throws Exception {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        source.setTicketId(null);
        source.setPortalId(null);
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));

        service.repeatDueNotifications();

        verify(notificationRepository, times(1)).save(any(NotificationModel.class));
        verify(notificationPublisher).wakeAfterCommit(List.of(USER_ID));
        verify(emailDeliveryService, never()).sendUserNotification(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Повтор: удалённый получатель — email не падает, in-app — да")
    void repeat_deletedRecipient_emailSkipped() throws Exception {
        NotificationModel source = source(Instant.now().minusSeconds(31 * 60));
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source));
        when(userDomainService.getUsersByIds(anySet())).thenReturn(Map.of());

        service.repeatDueNotifications();

        verify(notificationRepository, times(1)).save(any(NotificationModel.class));
        verify(notificationPublisher).wakeAfterCommit(List.of(USER_ID));
        verify(emailDeliveryService, never()).sendUserNotification(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Повтор: несколько получателей — все получают in-app и email")
    void repeat_multipleRecipients() throws Exception {
        NotificationModel source1 = source(Instant.now().minusSeconds(31 * 60));
        source1.setRecipientId(USER_ID);
        NotificationModel source2 = source(Instant.now().minusSeconds(31 * 60));
        source2.setRecipientId(USER_2_ID);
        when(notificationRepository.findRepeatCandidates(anySet(), any(Pageable.class))).thenReturn(List.of(source1, source2));
        UserModel recipient1 = new UserModel();
        recipient1.setId(USER_ID);
        recipient1.setEmail("user1@example.com");
        UserModel recipient2 = new UserModel();
        recipient2.setId(USER_2_ID);
        recipient2.setEmail("user2@example.com");
        when(userDomainService.getUsersByIds(anySet())).thenReturn(Map.of(USER_ID, recipient1, USER_2_ID, recipient2));

        service.repeatDueNotifications();

        verify(notificationRepository, times(2)).save(any(NotificationModel.class));
        verify(notificationPublisher).wakeAfterCommit(any());
        verify(emailDeliveryService, times(1)).sendUserNotification(eq("user1@example.com"), any(), any(), any(), eq(USER_ID));
        verify(emailDeliveryService, times(1)).sendUserNotification(eq("user2@example.com"), any(), any(), any(), eq(USER_2_ID));
    }

    private NotificationModel source(Instant createdAt) {
        NotificationModel source = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, TICKET_ID, PORTAL_ID, "Проблема");
        source.setId(SOURCE_ID);
        source.setCreatedAt(createdAt);
        return source;
    }

    private UserNotificationPreferencesModel preferences(boolean repeatEnabled, int intervalMinutes, Set<NotificationEvent> events) {
        UserNotificationPreferencesModel preferences = new UserNotificationPreferencesModel(USER_ID, events);
        preferences.setRepeatEnabled(repeatEnabled);
        preferences.setRepeatIntervalMinutes(intervalMinutes);
        return preferences;
    }
}

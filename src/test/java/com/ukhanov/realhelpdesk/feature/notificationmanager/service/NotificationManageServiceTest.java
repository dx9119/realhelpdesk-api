package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterCapture;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.exception.PaginationException;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;
import com.ukhanov.realhelpdesk.domain.notification.repository.NotificationRepository;
import com.ukhanov.realhelpdesk.domain.notification.repository.UserNotificationPreferencesRepository;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesRequest;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.UnreadCountResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.exception.NotificationException;
import com.ukhanov.realhelpdesk.feature.notificationmanager.mapper.NotificationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Чтение in-app оповещений (NotificationManageService)")
class NotificationManageServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 43L;
    private static final Long NOTIFICATION_ID = 7L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserNotificationPreferencesRepository preferencesRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private NotificationWaitRegistry waitRegistry;

    private NotificationManageService service;

    @BeforeEach
    void setUp() {
        service = new NotificationManageService(notificationRepository, preferencesRepository, currentUserProvider, new PaginationAdapter(),
                waitRegistry, Mappers.getMapper(NotificationMapper.class));
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    // ────────────────────────────────────────────────
    // Список и счётчик
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Список: unreadOnly=true идёт в непрочитанные, маппинг в PageResponse")
    void getNotifications_unreadOnly_returnsUnreadPageResponse() {

        // given
        // when
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "Проблема с оплатой");
        when(notificationRepository.findByRecipientIdAndReadFalse(eq(USER_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(model), PageRequest.of(0, 10), 1));

        PageResponse<NotificationResponse> response = service.getNotifications(0, 10, "createdAt", "desc", true);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).event()).isEqualTo(NotificationEvent.NEW_TICKET);
        assertThat(response.content().get(0).title()).isEqualTo("Проблема с оплатой");
        assertThat(response.totalElements()).isEqualTo(1);
        verify(notificationRepository, never()).findByRecipientId(eq(USER_ID), any(PageRequest.class));
    }

    @Test
    @DisplayName("Список: непрочитанные=false идёт в общий список")
    void getNotifications_unreadOnlyFalse_usesGeneralQuery() {

        // given
        // when
        when(notificationRepository.findByRecipientId(eq(USER_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        PageResponse<NotificationResponse> response = service.getNotifications(0, 10, "createdAt", "desc", false);

        // then
        assertThat(response.content()).isEmpty();
        verify(notificationRepository, never()).findByRecipientIdAndReadFalse(eq(USER_ID), any(PageRequest.class));
    }

    @Test
    @DisplayName("Список: недопустимое поле сортировки → PaginationException")
    void getNotifications_badSortField_throwsPaginationException() {

        // given / when / then
        assertThatThrownBy(() -> service.getNotifications(0, 10, "recipientId", "desc", false)).isInstanceOf(PaginationException.class);
        verify(notificationRepository, never()).findByRecipientId(any(), any(PageRequest.class));
    }

    @Test
    @DisplayName("Счётчик непрочитанных возвращает число из репозитория")
    void getUnreadCount_unreadPresent_returnsRepositoryCount() {

        // given
        // when
        when(notificationRepository.countByRecipientIdAndReadFalse(USER_ID)).thenReturn(5L);

        UnreadCountResponse response = service.getUnreadCount();

        // then
        assertThat(response.count()).isEqualTo(5L);
    }

    // ────────────────────────────────────────────────
    // Прочтение
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("markRead: чужое или несуществующее уведомление → 404")
    void markRead_notOwned_throwsNotFound() {

        // given
        // when
        when(notificationRepository.findByIdAndRecipientId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.empty());

        // then
        assertThatThrownBy(() -> service.markRead(NOTIFICATION_ID)).isInstanceOf(NotificationException.class)
                .hasMessageContaining("не найдено");
        verify(notificationRepository, never()).markGroupReadByRecipientId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("markRead: непрочитанный оригинал гасит группу по его id")
    void markRead_unread_marksGroupById() throws NotificationException {

        // given
        // when
        NotificationModel model = mock(NotificationModel.class);
        when(model.getId()).thenReturn(NOTIFICATION_ID);
        when(model.getGroupId()).thenReturn(null);
        when(model.isRead()).thenReturn(false);
        when(notificationRepository.findByIdAndRecipientId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.of(model));

        service.markRead(NOTIFICATION_ID);

        // then
        verify(notificationRepository).markGroupReadByRecipientId(USER_ID, NOTIFICATION_ID);
        verify(notificationRepository, never()).save(any(NotificationModel.class));
    }

    @Test
    @DisplayName("markRead: напоминание гасит группу по group_id первоисточника")
    void markRead_reminder_marksSourceGroup() throws NotificationException {

        // given
        // when
        NotificationModel model = mock(NotificationModel.class);
        when(model.getId()).thenReturn(NOTIFICATION_ID);
        when(model.getGroupId()).thenReturn(55L);
        when(model.isRead()).thenReturn(false);
        when(notificationRepository.findByIdAndRecipientId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.of(model));

        service.markRead(NOTIFICATION_ID);

        // then
        verify(notificationRepository).markGroupReadByRecipientId(USER_ID, 55L);
    }

    @Test
    @DisplayName("markRead: уже прочитанное → группа не трогается")
    void markRead_alreadyRead_skipsUpdate() throws NotificationException {

        // given
        // when
        NotificationModel model = mock(NotificationModel.class);
        when(model.getId()).thenReturn(NOTIFICATION_ID);
        when(model.isRead()).thenReturn(true);
        when(notificationRepository.findByIdAndRecipientId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.of(model));

        service.markRead(NOTIFICATION_ID);

        // then
        verify(notificationRepository, never()).markGroupReadByRecipientId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("markAllRead: массовое обновление через репозиторий")
    void markAllRead_unreadRowsPresent_delegatesToRepository() {

        // given
        // when
        when(notificationRepository.markAllReadByRecipientId(USER_ID)).thenReturn(3);

        service.markAllRead();

        // then
        verify(notificationRepository).markAllReadByRecipientId(USER_ID);
    }

    // ────────────────────────────────────────────────
    // Настройки событий
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Настройки без сохранённой строки → все события каталога, повтор включён на 30 минут")
    void getPreferences_noSavedRow_returnsCatalogDefaults() {

        // given
        // when
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        NotificationPreferencesResponse response = service.getPreferences();

        // then
        assertThat(response.events()).containsExactlyInAnyOrderElementsOf(NotificationPublisher.SUPPORTED_EVENTS);
        assertThat(response.repeatEnabled()).isTrue();
        assertThat(response.repeatIntervalMinutes()).isEqualTo(UserNotificationPreferencesModel.DEFAULT_REPEAT_INTERVAL_MINUTES);
    }

    @Test
    @DisplayName("Настройки: сохранённая строка возвращается как есть, включая повтор")
    void getPreferences_savedRow_returnsStoredValues() {

        // given
        // when
        UserNotificationPreferencesModel saved = new UserNotificationPreferencesModel(USER_ID, Set.of(NotificationEvent.NEW_MESSAGE));
        saved.setRepeatEnabled(false);
        saved.setRepeatIntervalMinutes(5);
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(saved));

        NotificationPreferencesResponse response = service.getPreferences();

        // then
        assertThat(response.events()).containsExactly(NotificationEvent.NEW_MESSAGE);
        assertThat(response.repeatEnabled()).isFalse();
        assertThat(response.repeatIntervalMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Настройки: событие вне каталога → 400")
    void updatePreferences_unsupportedEvent_throwsBadRequest() {

        // given
        // when
        NotificationPreferencesRequest request = new NotificationPreferencesRequest(Set.of(NotificationEvent.RECOVERY_PASSWORD), null,
                null);

        // then
        assertThatThrownBy(() -> service.updatePreferences(request)).isInstanceOf(NotificationException.class)
                .hasMessageContaining("Неподдерживаемые события");
        verify(preferencesRepository, never()).save(any(UserNotificationPreferencesModel.class));
    }

    @Test
    @DisplayName("Настройки: без строки создаётся с дефолтами повтора, со строкой — повтор меняется")
    void updatePreferences_missingThenExistingRow_createsThenUpdates() throws NotificationException {

        // given
        // when
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        NotificationPreferencesRequest request = new NotificationPreferencesRequest(Set.of(NotificationEvent.NEW_TICKET), null, null);

        NotificationPreferencesResponse created = service.updatePreferences(request);

        // then
        assertThat(created.events()).containsExactly(NotificationEvent.NEW_TICKET);
        assertThat(created.repeatEnabled()).isTrue();
        assertThat(created.repeatIntervalMinutes()).isEqualTo(UserNotificationPreferencesModel.DEFAULT_REPEAT_INTERVAL_MINUTES);
        ArgumentCaptor<UserNotificationPreferencesModel> captor = ArgumentCaptor.forClass(UserNotificationPreferencesModel.class);
        verify(preferencesRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getEnabledEvents()).containsExactly(NotificationEvent.NEW_TICKET);
        assertThat(captor.getValue().isRepeatEnabled()).isTrue();

        UserNotificationPreferencesModel existing = new UserNotificationPreferencesModel(USER_ID, Set.of(NotificationEvent.NEW_TICKET));
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        NotificationPreferencesRequest second = new NotificationPreferencesRequest(Set.of(NotificationEvent.CHANGE_TICKET), false, 5);

        NotificationPreferencesResponse updated = service.updatePreferences(second);

        assertThat(updated.events()).containsExactly(NotificationEvent.CHANGE_TICKET);
        assertThat(existing.getEnabledEvents()).containsExactly(NotificationEvent.CHANGE_TICKET);
        assertThat(updated.repeatEnabled()).isFalse();
        assertThat(updated.repeatIntervalMinutes()).isEqualTo(5);
        assertThat(existing.isRepeatEnabled()).isFalse();
        assertThat(existing.getRepeatIntervalMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Настройки: null полей повтора не меняет сохранённые значения")
    void updatePreferences_nullRepeat_keepsCurrent() throws NotificationException {

        // given
        // when
        UserNotificationPreferencesModel existing = new UserNotificationPreferencesModel(USER_ID, Set.of(NotificationEvent.NEW_TICKET));
        existing.setRepeatEnabled(false);
        existing.setRepeatIntervalMinutes(7);
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        NotificationPreferencesResponse response = service
                .updatePreferences(new NotificationPreferencesRequest(Set.of(NotificationEvent.NEW_TICKET), null, null));

        // then
        assertThat(response.repeatEnabled()).isFalse();
        assertThat(response.repeatIntervalMinutes()).isEqualTo(7);
        assertThat(existing.isRepeatEnabled()).isFalse();
        assertThat(existing.getRepeatIntervalMinutes()).isEqualTo(7);
    }

    // ────────────────────────────────────────────────
    // Long polling
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("wait: новые уже есть → ответ сразу, без регистрации в реестре")
    void waitForNew_newRowsAvailable_returnsImmediately() {

        // given
        // when
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "З");
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(5L), any(PageRequest.class)))
                .thenReturn(List.of(model));

        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = service.waitForNew(5L, 25, 10);

        // then
        assertThat(deferred.hasResult()).isTrue();
        assertThat(deferred.getResult()).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<PageResponse<NotificationResponse>> entity = (ResponseEntity<PageResponse<NotificationResponse>>) deferred
                .getResult();
        assertThat(entity.getBody().content()).hasSize(1);
        // регистрация была (защита от гонки), резолв — сразу, без пробуждения
        verify(waitRegistry).register(eq(USER_ID), any(Runnable.class));
        verify(waitRegistry, never()).wake(anyLong());
    }

    @Test
    @DisplayName("wait: новых нет → регистрация ожидателя, пробуждение отдаёт появившиеся оповещения")
    void waitForNew_noNewRows_registersAndResolvesOnWake() {

        // given
        // when
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "З");
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of()).thenReturn(List.of(model));

        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = service.waitForNew(0L, 25, 10);

        // then
        assertThat(deferred.hasResult()).isFalse();
        ArgumentCaptor<Runnable> onWakeCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(waitRegistry).register(eq(USER_ID), onWakeCaptor.capture());

        // имитация: публикатор будит ожидающего
        onWakeCaptor.getValue().run();

        assertThat(deferred.hasResult()).isTrue();
        @SuppressWarnings("unchecked")
        ResponseEntity<PageResponse<NotificationResponse>> entity = (ResponseEntity<PageResponse<NotificationResponse>>) deferred
                .getResult();
        assertThat(entity.getBody().content()).hasSize(1);
    }

    @Test
    @DisplayName("wait: пробуждение без новых строк не резолвитDeferredResult — клиент продолжает ждать")
    void waitForNew_wakeWithoutRows_keepsWaiting() {

        // given
        // when
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of()).thenReturn(List.of());

        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = service.waitForNew(0L, 25, 10);

        ArgumentCaptor<Runnable> onWakeCaptor = ArgumentCaptor.forClass(Runnable.class);
        // then
        verify(waitRegistry).register(eq(USER_ID), onWakeCaptor.capture());
        onWakeCaptor.getValue().run();

        assertThat(deferred.hasResult()).isFalse();
    }

    @Test
    @DisplayName("wait: курсор afterId и размер страницы уходят в запрос")
    void waitForNew_customCursorAndSize_passesToQuery() {

        // given
        // when
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        service.waitForNew(99L, 25, 5);

        // then
        verify(notificationRepository).findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(99L), eq(PageRequest.of(0, 5)));
    }

    // ────────────────────────────────────────────────
    // SSE-канал и общий с long polling механизм
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("stream: первый пакет — комментарий connected, подписка в реестре ожидающих")
    void subscribe_emptyBackfill_sendsConnectedCommentAndRegisters() {

        // given
        // when
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of());

        SseEmitter emitter = service.subscribe(0L, 10);
        SseEmitterCapture.Capture capture = SseEmitterCapture.attach(emitter);

        // then
        assertThat(capture.payloads()).hasSize(1);
        assertThat(capture.payloads().get(0)).isInstanceOf(String.class).asString().contains("connected");
        verify(waitRegistry).register(eq(USER_ID), any(Runnable.class));
        verify(waitRegistry, never()).unregister(anyLong(), any(Runnable.class));
    }

    @Test
    @DisplayName("stream: при подключении сразу отдаётся backfill всего новее курсора (event: notification)")
    void subscribe_existingRows_sendsBackfillOnConnect() {

        // given
        // when
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "З");
        model.setId(7L);
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of(model));

        SseEmitter emitter = service.subscribe(0L, 10);
        SseEmitterCapture.Capture capture = SseEmitterCapture.attach(emitter);

        // then
        assertThat(capture.payloads()).anyMatch(payload -> payload instanceof String text && text.contains("event:notification"));
        List<NotificationResponse> sent = capture.payloads().stream().filter(NotificationResponse.class::isInstance)
                .map(NotificationResponse.class::cast).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().event()).isEqualTo(NotificationEvent.NEW_TICKET);
        // канал пережил backfill: подписка осталась висеть до закрытия/таймаута
        verify(waitRegistry, never()).unregister(anyLong(), any(Runnable.class));
    }

    @Test
    @DisplayName("stream: wake отдаёт новые строки и заново ставит подписку в реестр (wake снимает ожидающих один раз)")
    void subscribe_wakeWithNewRows_pushesAndReregisters() {

        // given
        // when
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "З");
        model.setId(7L);
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of()).thenReturn(List.of(model));

        SseEmitter emitter = service.subscribe(0L, 10);
        SseEmitterCapture.Capture capture = SseEmitterCapture.attach(emitter);

        ArgumentCaptor<Runnable> wakeCaptor = ArgumentCaptor.forClass(Runnable.class);
        // then
        verify(waitRegistry).register(eq(USER_ID), wakeCaptor.capture());

        // имитация: публикатор будит подписчика
        wakeCaptor.getValue().run();

        verify(waitRegistry, times(2)).register(eq(USER_ID), any(Runnable.class));
        assertThat(capture.payloads().stream().filter(NotificationResponse.class::isInstance)).hasSize(1);
    }

    @Test
    @DisplayName("stream: закрытие канала снимает подписку с реестра")
    void subscribe_channelCompleted_unregistersWaiter() {

        // given
        // when
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of());

        SseEmitter emitter = service.subscribe(0L, 10);
        SseEmitterCapture.attach(emitter);

        emitter.complete();

        // then
        verify(waitRegistry).unregister(eq(USER_ID), any(Runnable.class));
    }

    @Test
    @DisplayName("stream: таймаут канала снимает подписку с реестра")
    void subscribe_channelTimedOut_unregistersWaiter() {

        // given
        // when
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of());

        SseEmitter emitter = service.subscribe(0L, 10);
        SseEmitterCapture.Capture capture = SseEmitterCapture.attach(emitter);

        capture.fireTimeout();

        // then
        verify(waitRegistry).unregister(eq(USER_ID), any(Runnable.class));
    }

    @Test
    @DisplayName("Фолбэк: одно wake будит и long polling, и SSE из одного реестра ожидающих")
    void wake_sharedRegistry_drivesBothChannels() {

        // given
        // when
        NotificationWaitRegistry realRegistry = new NotificationWaitRegistry();
        NotificationManageService sharedService = new NotificationManageService(notificationRepository, preferencesRepository,
                currentUserProvider, new PaginationAdapter(), realRegistry, Mappers.getMapper(NotificationMapper.class));
        NotificationModel model = new NotificationModel(USER_ID, NotificationEvent.NEW_TICKET, 11L, null, "З");
        model.setId(7L);
        // порядок выборок: backfill подписки и немедленная проверка wait — пусто; после wake — строки обоим
        when(notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(eq(USER_ID), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of()).thenReturn(List.of()).thenReturn(List.of(model)).thenReturn(List.of(model));

        SseEmitter emitter = sharedService.subscribe(0L, 10);
        SseEmitterCapture.attach(emitter);
        // then
        assertThat(realRegistry.waiterCount(USER_ID)).isEqualTo(1);

        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = sharedService.waitForNew(0L, 25, 10);
        assertThat(deferred.hasResult()).isFalse();
        assertThat(realRegistry.waiterCount(USER_ID)).isEqualTo(2);

        // публикатор: пишет в БД и будит всех ожидающих пользователя после коммита
        realRegistry.wake(USER_ID);

        // long polling получил строки, SSE заново встал в реестр до закрытия канала
        assertThat(deferred.hasResult()).isTrue();
        @SuppressWarnings("unchecked")
        ResponseEntity<PageResponse<NotificationResponse>> entity = (ResponseEntity<PageResponse<NotificationResponse>>) deferred
                .getResult();
        assertThat(entity.getBody().content()).hasSize(1);
        assertThat(realRegistry.waiterCount(USER_ID)).isEqualTo(1);

        emitter.complete();
        assertThat(realRegistry.waiterCount(USER_ID)).isZero();
    }
}

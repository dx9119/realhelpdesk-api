package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Чтение in-app оповещений: список, счётчик непрочитанных, отметка о прочтении, настройки событий, SSE-канал и long
 * polling (фолбэк). Публикация — {@link NotificationPublisher}; ожидающих будит он же через
 * {@link NotificationWaitRegistry}.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class NotificationManageService {

    private static final Set<String> SORTABLE_NOTIFICATION_FIELDS = Set.of("createdAt");

    private final NotificationRepository notificationRepository;
    private final UserNotificationPreferencesRepository preferencesRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PaginationAdapter paginationAdapter;
    private final NotificationWaitRegistry waitRegistry;
    private final NotificationMapper notificationMapper;
    private final NotificationSseService sseService;

    public PageResponse<NotificationResponse> getNotifications(int page, int size, String sortBy, String order,
            boolean unreadOnly) {
        Long userId = currentUserProvider.getCurrentUserId();
        PageRequest pageRequest = paginationAdapter.buildPageRequest(page, size, sortBy, order,
                SORTABLE_NOTIFICATION_FIELDS);

        Page<NotificationModel> notifications = unreadOnly
                ? notificationRepository.findByRecipientIdAndReadFalse(userId, pageRequest)
                : notificationRepository.findByRecipientId(userId, pageRequest);

        return paginationAdapter.mapToResponse(notifications.map(notificationMapper::toResponse), sortBy, order);
    }

    public UnreadCountResponse getUnreadCount() {
        Long userId = currentUserProvider.getCurrentUserId();
        return new UnreadCountResponse(notificationRepository.countByRecipientIdAndReadFalse(userId));
    }

    /**
     * Long polling: отвечает сразу, если есть оповещения новее курсора {@code afterId}; иначе ждёт пробуждения от
     * публикатора до {@code timeoutSec} и возвращает пустую страницу по таймауту. Клиент передаёт id последнего
     * полученного оповещения.
     */
    public DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> waitForNew(long afterId, int timeoutSec,
            int size) {
        Long userId = currentUserProvider.getCurrentUserId();
        // MDC — thread-local: колбэк вызовет публикатор на своём потоке, поэтому контекст запроса
        // переносим снимком.
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = new DeferredResult<>(
                Duration.ofSeconds(timeoutSec).toMillis(), ResponseEntity.ok(emptyPage(size)));

        Runnable onWake = () -> {
            // пробуждение придёт на потоке публикатора или планировщика: подставляем контекст запроса,
            // который ждёт ответа, а в finally возвращаем прежний контекст будящего потока —
            // им может оказаться поток чужого HTTP-запроса, у которого своя строка access-лога
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                completeIfReady(deferred, userId, afterId, size);
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };

        waitRegistry.register(userId, onWake);
        deferred.onCompletion(() -> waitRegistry.unregister(userId, onWake));
        deferred.onTimeout(() -> waitRegistry.unregister(userId, onWake));

        // уже есть новые оповещения — отвечаем сразу, оставаясь на потоке запроса (MDC уже стоит)
        completeIfReady(deferred, userId, afterId, size);
        logger.debug("Long polling оповещений: пользователь {}, afterId {}, таймаут {} сек", userId, afterId,
                timeoutSec);
        return deferred;
    }

    /** Общая часть ответа long polling: и немедленная проверка, и пробуждение из реестра. */
    private void completeIfReady(DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred,
            Long userId, long afterId,
            int size) {
        if (deferred.isSetOrExpired()) {
            return;
        }
        PageResponse<NotificationResponse> fresh = fetchAfter(userId, afterId, size);
        if (!fresh.content().isEmpty()) {
            deferred.setResult(ResponseEntity.ok(fresh));
        }
    }

    /**
     * SSE-канал оповещений: одно долгоживущее соединение вместо висящего long-polling запроса (long polling остаётся
     * фолбэком для клиентов без EventSource).
     *
     * <p>
     * События отдаются как {@code event: notification} с телом {@link NotificationResponse}; курсор (id последнего
     * отправленного оповещения) ведёт подписка, поэтому повторных выдач нет. Канал живёт до таймаута или разрыва
     * клиентом и после каждого события снова встаёт в реестр ожидающих (реестр будит подписчика один раз — см.
     * {@link NotificationWaitRegistry}).
     */
    public SseEmitter subscribe(long afterId, int size) {
        return sseService.subscribe(afterId, size);
    }

    public void markRead(Long notificationId) throws NotificationException {
        Objects.requireNonNull(notificationId, "notificationId не должен быть null");
        Long userId = currentUserProvider.getCurrentUserId();

        NotificationModel notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> NotificationException.notFound("Уведомление не найдено"));

        if (!notification.isRead()) {
            // прочтение строки гасит всю её группу повторов: напоминания о том же событии больше не нужны
            Long groupId = notification.getGroupId() != null ? notification.getGroupId() : notification.getId();
            int updated = notificationRepository.markGroupReadByRecipientId(userId, groupId);
            logger.debug("Уведомление {} пользователя {} отмечено прочитанным вместе с группой: строк {}",
                    notificationId, userId, updated);
        }
    }

    public void markAllRead() {
        Long userId = currentUserProvider.getCurrentUserId();
        int updated = notificationRepository.markAllReadByRecipientId(userId);
        logger.debug("Отмечено прочитанными {} уведомлений пользователя {}", updated, userId);
    }

    public NotificationPreferencesResponse getPreferences() {
        Long userId = currentUserProvider.getCurrentUserId();
        return preferencesRepository.findByUserId(userId)
                .map(preferences -> new NotificationPreferencesResponse(preferences.getEnabledEvents(),
                        preferences.isRepeatEnabled(),
                        preferences.getRepeatIntervalMinutes()))
                .orElseGet(() -> new NotificationPreferencesResponse(NotificationPublisher.SUPPORTED_EVENTS,
                        UserNotificationPreferencesModel.DEFAULT_REPEAT_ENABLED,
                        UserNotificationPreferencesModel.DEFAULT_REPEAT_INTERVAL_MINUTES));
    }

    public NotificationPreferencesResponse updatePreferences(NotificationPreferencesRequest request)
            throws NotificationException {
        Objects.requireNonNull(request, "request не должен быть null");
        Long userId = currentUserProvider.getCurrentUserId();

        Set<NotificationEvent> events = request.events();
        if (!NotificationPublisher.SUPPORTED_EVENTS.containsAll(events)) {
            throw NotificationException.badRequest("Неподдерживаемые события оповещений: " + unsupported(events));
        }

        UserNotificationPreferencesModel preferences = preferencesRepository.findByUserId(userId).orElse(null);
        boolean repeatEnabled = repeatEnabled(request, preferences);
        int repeatIntervalMinutes = repeatIntervalMinutes(request, preferences);

        if (preferences == null) {
            preferences = new UserNotificationPreferencesModel(userId, events);
        }
        preferences.setEnabledEvents(events);
        preferences.setRepeatEnabled(repeatEnabled);
        preferences.setRepeatIntervalMinutes(repeatIntervalMinutes);
        preferencesRepository.save(preferences);
        logger.info("Пользователь {} обновил настройки оповещений: {} событий, повтор {} каждые {} мин", userId,
                events.size(),
                repeatEnabled ? "включён" : "выключен", repeatIntervalMinutes);

        return new NotificationPreferencesResponse(preferences.getEnabledEvents(), repeatEnabled,
                repeatIntervalMinutes);
    }

    // null в запросе — не менять: у существующей строки берётся её значение, у новой — дефолт
    private boolean repeatEnabled(NotificationPreferencesRequest request,
            UserNotificationPreferencesModel preferences) {
        if (request.repeatEnabled() != null) {
            return request.repeatEnabled();
        }
        return preferences != null
                ? preferences.isRepeatEnabled()
                : UserNotificationPreferencesModel.DEFAULT_REPEAT_ENABLED;
    }

    private int repeatIntervalMinutes(NotificationPreferencesRequest request,
            UserNotificationPreferencesModel preferences) {
        if (request.repeatIntervalMinutes() != null) {
            return request.repeatIntervalMinutes();
        }
        return preferences != null
                ? preferences.getRepeatIntervalMinutes()
                : UserNotificationPreferencesModel.DEFAULT_REPEAT_INTERVAL_MINUTES;
    }

    private PageResponse<NotificationResponse> fetchAfter(Long userId, long afterId, int size) {
        List<NotificationModel> rows = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(userId,
                afterId,
                PageRequest.of(0, size));
        List<NotificationResponse> content = rows.stream().map(notificationMapper::toResponse).toList();

        return new PageResponse.Builder<NotificationResponse>().content(content)
                .page(0)
                .size(size)
                .totalElements(content.size())
                .totalPages(content.isEmpty() ? 0 : 1)
                .last(true)
                .build();
    }

    private PageResponse<NotificationResponse> emptyPage(int size) {
        return new PageResponse.Builder<NotificationResponse>().content(List.of())
                .page(0)
                .size(size)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .build();
    }

    private List<String> unsupported(Set<NotificationEvent> events) {
        return events.stream()
                .filter(event -> !NotificationPublisher.SUPPORTED_EVENTS.contains(event))
                .map(Enum::name)
                .sorted()
                .toList();
    }
}

package com.ukhanov.realhelpdesk.feature.notificationmanager.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesRequest;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.UnreadCountResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.exception.NotificationException;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationManageService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationManageService notificationManageService;
    /** Список оповещений пользователя (все или только непрочитанные), новые сверху. */
    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> getNotifications(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order, @RequestParam(defaultValue = "false") boolean unreadOnly) {

        return ResponseEntity.ok(notificationManageService.getNotifications(page, size, sortBy, order, unreadOnly));
    }

    /** Счётчик непрочитанных — для значка уведомлений. no-store: значение меняется от любого события. */
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notificationManageService.getUnreadCount());
    }

    /**
     * SSE-канал: одно долгоживущее соединение вместо висящего long-polling запроса (клиент подписывается через EventSource, события
     * приходят как {@code event: notification}). Long polling ниже оставлен фолбэком: он держит запрос 25 секунд и делит лимит на IP между
     * всеми вкладками.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return notificationManageService.subscribe(afterId, size);
    }

    /**
     * Long polling: отвечает сразу при наличии оповещений новее {@code afterId}, иначе ждёт до {@code timeoutSec} (25 по умолчанию) и
     * возвращает пустую страницу. Клиент передаёт id последнего полученного оповещения курсором.
     */
    @GetMapping("/wait")
    @RateLimit(key = "notifications-wait")
    public DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> waitForNew(
            @RequestParam(defaultValue = "0") @Min(0) long afterId, @RequestParam(defaultValue = "25") @Min(1) @Max(30) int timeoutSec,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size, HttpServletResponse response) {

        // no-store: ответ long polling не должен осесть в прокси/браузере и вернуться как «свежий»
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return notificationManageService.waitForNew(afterId, timeoutSec, size);
    }

    @PutMapping("/{id}/read")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> markRead(@PathVariable Long id) throws NotificationException {
        notificationManageService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/read-all")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> markAllRead() {
        notificationManageService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferencesResponse> getPreferences() {
        return ResponseEntity.ok(notificationManageService.getPreferences());
    }

    /**
     * Задать события оповещений (полный набор in-app событий — в ответе GET preferences) и повтор непрочитанных NEW_TICKET/NEW_MESSAGE:
     * {@code repeatEnabled} и интервал {@code repeatIntervalMinutes} (1..10080); null — не менять.
     */
    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferencesResponse> updatePreferences(@Valid @RequestBody NotificationPreferencesRequest request)
            throws NotificationException {
        return ResponseEntity.ok(notificationManageService.updatePreferences(request));
    }
}

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationManageService notificationManageService;

    @Operation(summary = "Список оповещений",
            description = "Возвращает оповещения пользователя (все или только непрочитанные), "
                    + "новые сверху. Пагинированно.")
    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> getNotifications(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {

        return ResponseEntity.ok(notificationManageService.getNotifications(page, size, sortBy, order, unreadOnly));
    }

    @Operation(summary = "Счётчик непрочитанных",
            description = "Для значка уведомлений. no-store: значение меняется от любого события.")
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(notificationManageService.getUnreadCount());
    }

    @Operation(summary = "SSE-канал уведомлений",
            description = "Одно долгоживущее соединение: клиент подписывается через EventSource, события "
                    + "приходят как event: notification. Long polling ниже оставлен фолбэком.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(responseCode = "200", description = "Поток событий Server-Sent Events (событие notification)",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    public SseEmitter stream(@RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return notificationManageService.subscribe(afterId, size);
    }

    @Operation(summary = "Long polling оповещений",
            description = "Отвечает сразу при наличии оповещений новее afterId, иначе ждёт до timeoutSec "
                    + "(25 по умолчанию) и возвращает пустую страницу. "
                    + "Клиент передаёт id последнего оповещения курсором.")
    @GetMapping("/wait")
    @RateLimit(key = "notifications-wait")
    public DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> waitForNew(
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "25") @Min(1) @Max(30) int timeoutSec,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size, HttpServletResponse response) {

        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return notificationManageService.waitForNew(afterId, timeoutSec, size);
    }

    @Operation(summary = "Отметить прочитанным", description = "Гасит группу повторов. Возвращает 204 без тела.")
    @PutMapping("/{id}/read")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> markRead(@PathVariable Long id) throws NotificationException {
        notificationManageService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Прочитать все",
            description = "Отмечает все оповещения прочитанными. Возвращает 204 без тела.")
    @PutMapping("/read-all")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> markAllRead() {
        notificationManageService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Предпочтения оповещений", description = "Возвращает включённые события и настройки повтора.")
    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferencesResponse> getPreferences() {
        return ResponseEntity.ok(notificationManageService.getPreferences());
    }

    @Operation(summary = "Настройка предпочтений оповещений",
            description = "Задать события оповещений и повтор непрочитанных NEW_TICKET/NEW_MESSAGE: "
                    + "repeatEnabled и интервал repeatIntervalMinutes (1..10080); null — не менять.")
    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferencesResponse> updatePreferences(
            @Valid @RequestBody NotificationPreferencesRequest request)
            throws NotificationException {
        return ResponseEntity.ok(notificationManageService.updatePreferences(request));
    }
}

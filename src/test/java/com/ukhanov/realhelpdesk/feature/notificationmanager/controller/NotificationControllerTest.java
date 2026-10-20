package com.ukhanov.realhelpdesk.feature.notificationmanager.controller;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesRequest;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationPreferencesResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.UnreadCountResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.exception.NotificationException;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты REST-контракта оповещений (NotificationController)")
class NotificationControllerTest {

    private static final Long NOTIFICATION_ID = 7L;

    @Mock
    private NotificationManageService mockNotificationManageService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        NotificationController controller = new NotificationController(mockNotificationManageService);
        mockMvc = MockMvcTester.of(List.of(validatedController(controller)),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    /**
     * В приложении @Validated обрабатывает MethodValidationPostProcessor из ValidationAutoConfiguration. В standalone-тесте контекста нет,
     * поэтому прокси создаём тем же пост-процессором вручную (как в TicketSearchControllerTest).
     */
    private NotificationController validatedController(NotificationController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        processor.afterPropertiesSet();

        return (NotificationController) processor.postProcessAfterInitialization(controller, "notificationController");
    }

    // ────────────────────────────────────────────────
    // Список и счётчик
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/notifications → 200 со списком оповещений")
    void getNotifications_defaultPaging_returnsNotificationPage() {
        // given
        NotificationResponse response = new NotificationResponse(1L, NotificationEvent.NEW_TICKET, 11L, 2L, "Проблема с оплатой", false,
                Instant.parse("2026-10-05T10:00:00Z"));
        when(mockNotificationManageService.getNotifications(0, 10, "createdAt", "desc", false)).thenReturn(pageOf(response));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/notifications").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.content[0].id").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].event").asString().isEqualTo("NEW_TICKET");
        assertThat(result).bodyJson().extractingPath("$.content[0].title").asString().isEqualTo("Проблема с оплатой");
        assertThat(result).bodyJson().extractingPath("$.content[0].read").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.totalElements").asNumber().isEqualTo(1);

        verify(mockNotificationManageService).getNotifications(0, 10, "createdAt", "desc", false);
    }

    @Test
    @DisplayName("GET /api/v1/notifications?unreadOnly=true → проброс фильтра в сервис")
    void getNotifications_unreadOnlyTrue_passesFilterToService() {
        // given
        when(mockNotificationManageService.getNotifications(1, 5, "createdAt", "desc", true)).thenReturn(pageOf());

        // when / then
        assertThat(mockMvc.get().uri("/api/v1/notifications").param("page", "1").param("size", "5").param("unreadOnly", "true"))
                .hasStatusOk();

        verify(mockNotificationManageService).getNotifications(1, 5, "createdAt", "desc", true);
    }

    @Test
    @DisplayName("GET /api/v1/notifications?page=-1 → 400")
    void getNotifications_negativePage_badRequest() {
        // given / when / then
        assertThat(mockMvc.get().uri("/api/v1/notifications").param("page", "-1")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /unread-count → 200 со счётчиком")
    void getUnreadCount_unreadExists_returnsCount() {
        // given
        when(mockNotificationManageService.getUnreadCount()).thenReturn(new UnreadCountResponse(3L));

        // when / then
        MvcTestResult result = mockMvc.get().uri("/api/v1/notifications/unread-count").exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.count").asNumber().isEqualTo(3);
    }

    // ────────────────────────────────────────────────
    // Long polling
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /wait → async: ответ отдаётся, когда резолвится DeferredResult")
    void waitForNew_pendingDeferredResult_resolvesWithPage() {
        // given
        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = new DeferredResult<>();
        when(mockNotificationManageService.waitForNew(eq(5L), anyInt(), anyInt())).thenReturn(deferred);

        // when
        MvcTestResult pending = mockMvc.get().uri("/api/v1/notifications/wait").param("afterId", "5").asyncExchange();
        assertThat(pending).request().hasAsyncStarted(true);

        NotificationResponse response = new NotificationResponse(9L, NotificationEvent.CHANGE_TICKET, 11L, 2L, "Заявка", false,
                Instant.parse("2026-10-05T10:00:00Z"));
        deferred.setResult(ResponseEntity.ok(pageOf(response)));

        // then
        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(pending.getMvcResult()));
        assertThat(resolved).hasStatusOk();
        assertThat(resolved).bodyJson().extractingPath("$.content[0].id").asNumber().isEqualTo(9);
        assertThat(resolved).bodyJson().extractingPath("$.content[0].event").asString().isEqualTo("CHANGE_TICKET");
    }

    @Test
    @DisplayName("GET /wait → пустая страница по таймауту: 200 с content=[]")
    void waitForNew_timeout_emptyPage() {
        // given
        PageResponse<NotificationResponse> empty = pageOf();
        DeferredResult<ResponseEntity<PageResponse<NotificationResponse>>> deferred = new DeferredResult<>(25000L,
                ResponseEntity.ok(empty));
        when(mockNotificationManageService.waitForNew(anyLong(), anyInt(), anyInt())).thenReturn(deferred);

        // when
        MvcTestResult pending = mockMvc.get().uri("/api/v1/notifications/wait").asyncExchange();
        assertThat(pending).request().hasAsyncStarted(true);

        // имитация таймаута: Spring выставляет timeoutValue как результат (в сервисе это пустая страница)
        deferred.setResult(ResponseEntity.ok(empty));

        // then
        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(pending.getMvcResult()));
        assertThat(resolved).hasStatusOk();
        assertThat(resolved).bodyJson().extractingPath("$.content").asArray().isEmpty();
    }

    @Test
    @DisplayName("GET /wait?timeoutSec=99 → 400 (значение вне 1..30)")
    void waitForNew_timeoutOutOfRange_badRequest() {
        // given / when / then
        assertThat(mockMvc.get().uri("/api/v1/notifications/wait").param("timeoutSec", "99")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────
    // SSE-канал (основной канал, long polling — фолбэк)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /stream → async 200 text/event-stream, no-store, делегирует в сервис")
    void stream_subscribe_returnsEventStreamWithoutStore() throws Exception {
        // given
        SseEmitter emitter = new SseEmitter();
        // подготовительный пакет: без него заголовки ответа уходят только при первом оповещении
        emitter.send(SseEmitter.event().comment("connected"));
        when(mockNotificationManageService.subscribe(0L, 10)).thenReturn(emitter);

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/notifications/stream").asyncExchange();

        // then
        assertThat(result).request().hasAsyncStarted(true);
        assertThat(result).hasStatusOk().hasHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .hasContentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);

        verify(mockNotificationManageService).subscribe(0L, 10);
    }

    @Test
    @DisplayName("GET /stream?afterId=-1 → 400 (значение вне диапазона), в сервис не уходим")
    void stream_afterIdNegative_badRequest() {
        // given / when / then
        assertThat(mockMvc.get().uri("/api/v1/notifications/stream").param("afterId", "-1")).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(mockNotificationManageService);
    }

    @Test
    @DisplayName("GET /stream?size=51 → 400 (значение вне 1..50)")
    void stream_sizeOutOfRange_badRequest() {
        // given / when / then
        assertThat(mockMvc.get().uri("/api/v1/notifications/stream").param("size", "51")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────
    // Прочтение
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /{id}/read → 204")
    void markRead_existingNotification_returnsNoContent() throws NotificationException {
        // given / when / then
        assertThat(mockMvc.put().uri("/api/v1/notifications/{id}/read", NOTIFICATION_ID)).hasStatus(HttpStatus.NO_CONTENT);

        verify(mockNotificationManageService).markRead(NOTIFICATION_ID);
    }

    @Test
    @DisplayName("PUT /{id}/read → уведомление не найдено → 404 problem+json")
    void markRead_missingNotification_returnsNotFound() throws NotificationException {
        // given
        doThrow(NotificationException.notFound("Уведомление не найдено")).when(mockNotificationManageService).markRead(NOTIFICATION_ID);

        // when / then
        MvcTestResult result = mockMvc.put().uri("/api/v1/notifications/{id}/read", NOTIFICATION_ID).exchange();
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(404);
    }

    @Test
    @DisplayName("PUT /read-all → 204")
    void markAllRead_withNotifications_returnsNoContent() {
        // given / when / then
        assertThat(mockMvc.put().uri("/api/v1/notifications/read-all")).hasStatus(HttpStatus.NO_CONTENT);

        verify(mockNotificationManageService).markAllRead();
    }

    // ────────────────────────────────────────────────
    // Настройки
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /preferences → 200 с событиями и настройками повтора")
    void getPreferences_savedPreferences_returnsEventsAndRepeat() throws NotificationException {
        // given
        when(mockNotificationManageService.getPreferences())
                .thenReturn(new NotificationPreferencesResponse(Set.of(NotificationEvent.NEW_TICKET), true, 15));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/notifications/preferences").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.events[0]").asString().isEqualTo("NEW_TICKET");
        assertThat(result).bodyJson().extractingPath("$.repeatEnabled").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.repeatIntervalMinutes").asNumber().isEqualTo(15);
    }

    @Test
    @DisplayName("PUT /preferences → 200: события и настройки повтора возвращаются как сохранённые")
    void updatePreferences_validRequest_returnsSavedPreferences() throws NotificationException {
        // given
        when(mockNotificationManageService.updatePreferences(any(NotificationPreferencesRequest.class)))
                .thenReturn(new NotificationPreferencesResponse(Set.of(NotificationEvent.NEW_MESSAGE), false, 5));
        String body = "{\"events\":[\"NEW_MESSAGE\"],\"repeatEnabled\":false,\"repeatIntervalMinutes\":5}";

        // when
        MvcTestResult result = mockMvc.put().uri("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.events[0]").asString().isEqualTo("NEW_MESSAGE");
        assertThat(result).bodyJson().extractingPath("$.repeatEnabled").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.repeatIntervalMinutes").asNumber().isEqualTo(5);
    }

    @Test
    @DisplayName("PUT /preferences → пустой список событий → 400")
    void updatePreferences_emptyEvents_badRequest() {
        // given
        String body = "{\"events\":[]}";

        // when / then
        assertThat(mockMvc.put().uri("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PUT /preferences → неизвестное событие → 400")
    void updatePreferences_unknownEvent_badRequest() {
        // given
        String body = "{\"events\":[\"SOME_UNKNOWN_EVENT\"]}";

        // when / then
        assertThat(mockMvc.put().uri("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PUT /preferences → интервал повтора вне 1..10080 минут → 400")
    void updatePreferences_intervalOutOfRange_badRequest() {
        // given
        String tooSmall = "{\"events\":[\"NEW_TICKET\"],\"repeatIntervalMinutes\":0}";
        String tooLarge = "{\"events\":[\"NEW_TICKET\"],\"repeatIntervalMinutes\":10081}";

        // when / then
        assertThat(mockMvc.put().uri("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(tooSmall))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mockMvc.put().uri("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(tooLarge))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────

    private PageResponse<NotificationResponse> pageOf(NotificationResponse... content) {
        List<NotificationResponse> items = List.of(content);
        return new PageResponse.Builder<NotificationResponse>().content(items).page(0).size(10).totalElements(items.size())
                .totalPages(items.isEmpty() ? 0 : 1).last(true).build();
    }
}

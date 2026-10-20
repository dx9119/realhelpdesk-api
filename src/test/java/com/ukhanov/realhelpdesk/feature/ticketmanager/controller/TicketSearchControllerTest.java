package com.ukhanov.realhelpdesk.feature.ticketmanager.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.service.TicketSearchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты контроллера поиска заявок (TicketSearchController)")
class TicketSearchControllerTest {

    @Mock
    private TicketSearchService mockTicketSearchService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        TicketSearchController controller = new TicketSearchController(mockTicketSearchService);
        mockMvc = MockMvcTester.of(List.of(validatedController(controller)),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    /**
     * В приложении @Validated обрабатывает MethodValidationPostProcessor из ValidationAutoConfiguration. В standalone-тесте контекста нет,
     * поэтому прокси создаём тем же пост-процессором вручную.
     */
    private TicketSearchController validatedController(TicketSearchController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        processor.afterPropertiesSet();

        return (TicketSearchController) processor.postProcessAfterInitialization(controller, "ticketSearchController");
    }

    private void stubEmptyPage() {
        when(mockTicketSearchService.searchTickets(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new PageResponse.Builder<TicketResponse>().content(List.of()).page(0).size(10).totalElements(0).totalPages(0)
                        .last(true).build());
    }

    // ────────────────────────────────────────────────
    // Проброс параметров в сервис
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("search → все параметры пробрасываются в сервис как есть")
    void search_allFiltersSpecified_passesParamsToService() {
        // given
        stubEmptyPage();

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("search", "битый отчёт")
                .param("startDate", "2025-01-01T00:00:00Z").param("endDate", "2025-12-31T23:59:59Z").param("status", "OPEN")
                .param("priority", "HIGH").param("mine", "true").param("page", "2").param("size", "5").param("sortBy", "title")
                .param("order", "asc").exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketSearchService).searchTickets(eq("битый отчёт"), eq(Instant.parse("2025-01-01T00:00:00Z")),
                eq(Instant.parse("2025-12-31T23:59:59Z")), eq(TicketStatus.OPEN), eq(TicketPriority.HIGH), eq(true), eq(2), eq(5),
                eq("title"), eq("asc"));
    }

    @Test
    @DisplayName("search → без параметров → значения по умолчанию (null-фильтры, mine=false, page=0, size=10, createdAt desc)")
    void search_withoutParams_usesDefaults() {
        // given
        stubEmptyPage();

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketSearchService).searchTickets(isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(0), eq(10),
                eq("createdAt"), eq("desc"));
    }

    @Test
    @DisplayName("search → частично заполненные фильтры → незаполненные приходят в сервис как null")
    void search_partialFilters_passesNullsForMissing() {
        // given
        stubEmptyPage();

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("search", "баг").param("priority", "CRITICAL").exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketSearchService).searchTickets(eq("баг"), isNull(), isNull(), isNull(), eq(TicketPriority.CRITICAL), eq(false),
                eq(0), eq(10), eq("createdAt"), eq("desc"));
    }

    @Test
    @DisplayName("search → mine=true → единственный способ получить «мои заявки» (эндпоинт /tickets/mine удалён)")
    void search_mineTrue_isForwardedToService() {
        // given
        stubEmptyPage();

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("mine", "true").exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketSearchService).searchTickets(isNull(), isNull(), isNull(), isNull(), isNull(), eq(true), eq(0), eq(10),
                eq("createdAt"), eq("desc"));
    }

    // ────────────────────────────────────────────────
    // Ответ
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("search → возвращает страницу заявок из сервиса в JSON")
    void search_pageFromService_returnsJsonWithTicket() {
        // given
        TicketResponse ticket = new TicketResponse(42L, "Ошибка входа", "Тело", 3L, "Иванов Иван", "Портал", 7L, TicketPriority.HIGH,
                TicketStatus.OPEN, null, Instant.parse("2025-01-15T10:00:00Z"), 4);

        when(mockTicketSearchService.searchTickets(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new PageResponse.Builder<TicketResponse>().content(List.of(ticket)).page(0).size(10).totalElements(1)
                        .totalPages(1).last(true).build());

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("search", "вход").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.content[0].id").asNumber().isEqualTo(42);
        assertThat(result).bodyJson().extractingPath("$.content[0].title").asString().isEqualTo("Ошибка входа");
        assertThat(result).bodyJson().extractingPath("$.content[0].body").asString().isEqualTo("Тело");
        assertThat(result).bodyJson().extractingPath("$.content[0].authorId").asNumber().isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.content[0].portalId").asNumber().isEqualTo(7);
        assertThat(result).bodyJson().extractingPath("$.totalElements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.page").asNumber().isEqualTo(0);
    }

    // ────────────────────────────────────────────────
    // Некорректные параметры
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("search → строка поиска длиннее 50 символов → 400, сервис не вызывается")
    void search_tooLongSearch_returnsBadRequest() {
        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("search", "ы".repeat(51)).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.errors['searchTickets.search']").asString()
                .isEqualTo("Строка поиска не может превышать 50 символов");

        verifyNoInteractions(mockTicketSearchService);
    }

    @Test
    @DisplayName("search → строка ровно 50 символов → 200")
    void search_maxLengthSearch_passesValidation() {
        // given
        stubEmptyPage();

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("search", "ы".repeat(50)).exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketSearchService).searchTickets(eq("ы".repeat(50)), isNull(), isNull(), isNull(), isNull(), eq(false), eq(0), eq(10),
                eq("createdAt"), eq("desc"));
    }

    @Test
    @DisplayName("search → size больше 100 → 400, сервис не вызывается (верхняя граница есть у всех списков)")
    void search_tooLargeSize_returnsBadRequest() {
        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("size", "1000").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);
        assertThat(result).bodyJson().hasPath("$.errors['searchTickets.size']");

        verifyNoInteractions(mockTicketSearchService);
    }

    @Test
    @DisplayName("search → неизвестный статус заявки → 400, сервис не вызывается")
    void search_invalidStatus_returnsBadRequest() {
        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("status", "NOT_A_STATUS").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(mockTicketSearchService);
    }

    @Test
    @DisplayName("search → неизвестный приоритет заявки → 400, сервис не вызывается")
    void search_invalidPriority_returnsBadRequest() {
        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("priority", "URGENT").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(mockTicketSearchService);
    }

    @Test
    @DisplayName("search → дата в неверном формате → 400, сервис не вызывается")
    void search_invalidStartDate_returnsBadRequest() {
        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/tickets").param("startDate", "вчера").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(mockTicketSearchService);
    }
}

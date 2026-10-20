package com.ukhanov.realhelpdesk.feature.ticketmanager.controller;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.service.TicketManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты REST-контракта контроллера заявок (TicketController)")
class TicketControllerTest {

    private static final Long PORTAL_ID = 5L;
    private static final Long TICKET_ID = 77L;

    @Mock
    private TicketManageService mockTicketManageService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcTester.of(List.of(new TicketController(mockTicketManageService)),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    // ────────────────────────────────────────────────
    // Создание: 201 + Location
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /portals/{id}/tickets → 201 + Location на созданную заявку")
    void createTicket_validRequest_returnsCreatedWithLocation() throws Exception {
        // given
        when(mockTicketManageService.createTicket(any(CreateTicketRequest.class), eq(PORTAL_ID)))
                .thenReturn(new CreateTicketResponse(TICKET_ID));

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/portals/{portalId}/tickets", PORTAL_ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Ошибка входа\"}").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/api/v1/portals/5/tickets/77");
        assertThat(result).bodyJson().extractingPath("$.id").asNumber().isEqualTo(77);
    }

    @Test
    @DisplayName("POST /portals/{id}/tickets без темы → 400 (problem+json), сервис не вызывается")
    void createTicket_withoutTitle_returnsBadRequestProblem() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.post().uri("/api/v1/portals/{portalId}/tickets", PORTAL_ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.title").asString().isEqualTo("Bad Request");
        assertThat(result).bodyJson().hasPath("$.errors.title");

        verify(mockTicketManageService, never()).createTicket(any(), any());
    }

    // ────────────────────────────────────────────────
    // Обновление: PUT вместо POST
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /portals/{id}/tickets/{id}/status → 204")
    void setStatus_validStatus_returnsNoContent() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.put().uri("/api/v1/portals/{portalId}/tickets/{ticketId}/status", PORTAL_ID, TICKET_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);

        verify(mockTicketManageService).setTicketStatus(PORTAL_ID, TICKET_ID, TicketStatus.CLOSED);
    }

    @Test
    @DisplayName("PUT /portals/{id}/tickets/{id}/priority без тела → 400, сервис не вызывается")
    void setPriority_withoutBody_returnsBadRequest() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.put().uri("/api/v1/portals/{portalId}/tickets/{ticketId}/priority", PORTAL_ID, TICKET_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().hasPath("$.errors.priority");

        verify(mockTicketManageService, never()).setTicketPriority(any(), any(), any());
    }

    @Test
    @DisplayName("POST .../set/status/{id} (старый контракт) → 404")
    void setTicketStatus_legacyPath_returnsNotFound() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.post().uri("/api/v1/portals/{portalId}/ticket/set/status/{ticketId}", PORTAL_ID, TICKET_ID)
                .param("status", "CLOSED").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ────────────────────────────────────────────────
    // Удаление: DELETE + 204
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /portals/{id}/tickets/{id} → 204")
    void deleteTicket_existingTicket_returnsNoContent() throws Exception {
        // given / when / then
        assertThat(mockMvc.delete().uri("/api/v1/portals/{portalId}/tickets/{ticketId}", PORTAL_ID, TICKET_ID))
                .hasStatus(HttpStatus.NO_CONTENT);

        verify(mockTicketManageService).deleteTicket(TICKET_ID, PORTAL_ID);
    }

    // ────────────────────────────────────────────────
    // Чтение: фильтры вместо отдельных путей
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /portals/{id}/tickets без фильтра → страница из getPageTickets")
    void getPagedTickets_noFilter_returnsEmptyPage() throws Exception {
        // given
        when(mockTicketManageService.getPageTickets(eq(PORTAL_ID), eq(0), eq(10), eq("createdAt"), eq("desc")))
                .thenReturn(new PageResponse.Builder<TicketResponse>().content(List.of()).page(0).size(10).totalElements(0).totalPages(0)
                        .last(true).build());

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/portals/{portalId}/tickets", PORTAL_ID).exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.totalElements").asNumber().isEqualTo(0);
    }

    @Test
    @DisplayName("GET /portals/{id}/tickets?status=OPEN → фильтр по статусу через ids")
    void getPagedTickets_statusFilter_queriesByIdsOnly() throws Exception {
        // given
        when(mockTicketManageService.getIdTicketWithStatus(PORTAL_ID, TicketStatus.OPEN)).thenReturn(Set.of(1L, 2L));
        when(mockTicketManageService.getPageTicketsByIds(eq(Set.of(1L, 2L)), eq(0), eq(10), eq("createdAt"), eq("desc")))
                .thenReturn(new PageResponse.Builder<TicketResponse>().content(List.of()).page(0).size(10).totalElements(0).totalPages(0)
                        .last(true).build());

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/portals/{portalId}/tickets", PORTAL_ID).param("status", "OPEN").exchange();

        // then
        assertThat(result).hasStatusOk();

        verify(mockTicketManageService, never()).getPageTickets(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("GET /portals/{id}/tickets/ids?noAnswer=true → id без ответа")
    void getTicketIds_noAnswerFilter_returnsIdsWithoutAnswer() throws Exception {
        // given
        when(mockTicketManageService.getIdTicketNoAnswer(PORTAL_ID)).thenReturn(Set.of(9L));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/portals/{portalId}/tickets/ids", PORTAL_ID).param("noAnswer", "true").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0]").asNumber().isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.total").asNumber().isEqualTo(1);
    }
}

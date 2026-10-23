package com.ukhanov.realhelpdesk.feature.ticketmanager.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageCriteria;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketPriorityRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketStatusRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.service.TicketManageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals")
public class TicketController {

    private final TicketManageService ticketManageService;

    @Operation(summary = "Создание заявки",
            description = "Создаёт заявку в портале. Возвращает id и Location на созданный ресурс.")
    @PostMapping("/{portalId}/tickets")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<CreateTicketResponse> createTicketForPortal(@Valid @RequestBody CreateTicketRequest request,
            @PathVariable Long portalId)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        CreateTicketResponse response = ticketManageService.createTicket(request, portalId);
        return ResponseEntity.created(URI.create("/api/v1/portals/" + portalId + "/tickets/" + response.id()))
                .body(response);
    }

    @Operation(summary = "Заявки портала (пагинированно)",
            description = "Возвращает заявки портала с пагинацией; опциональная фильтрация по статусу.")
    @GetMapping("/{portalId}/tickets")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    public PageResponse<TicketResponse> getPagedTicketsByPortalId(@PathVariable Long portalId,
            @RequestParam(required = false) TicketStatus status,
            @Valid @ParameterObject @ModelAttribute PageCriteria pageCriteria)
            throws TicketException, PortalException {

        if (status == null) {
            return ticketManageService.getPageTickets(portalId, pageCriteria.page(), pageCriteria.size(),
                    pageCriteria.sortBy(), pageCriteria.order());
        }

        Set<Long> ids = ticketManageService.getIdTicketWithStatus(portalId, status);
        return ticketManageService.getPageTicketsByIds(ids, pageCriteria.page(), pageCriteria.size(),
                pageCriteria.sortBy(), pageCriteria.order());
    }

    @Operation(summary = "ID заявок портала",
            description = "Возвращает список id всех заявок портала; опциональная фильтрация "
                    + "по статусу и наличию ответа.")
    @GetMapping("/{portalId}/tickets/ids")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    public ItemsResponse<Long> getTicketIds(@PathVariable Long portalId,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(defaultValue = "false") boolean noAnswer) {

        if (noAnswer) {
            return ItemsResponse.of(ticketManageService.getIdTicketNoAnswer(portalId));
        }
        if (status != null) {
            return ItemsResponse.of(ticketManageService.getIdTicketWithStatus(portalId, status));
        }
        return ItemsResponse.of(ticketManageService.getAllTickets(portalId).stream().map(TicketResponse::id).toList());
    }

    @Operation(summary = "Заявка по ID", description = "Возвращает детальную информацию о заявке.")
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    @GetMapping("/{portalId}/tickets/{ticketId}")
    public TicketResponse getTicketById(@PathVariable Long portalId, @PathVariable Long ticketId)
            throws TicketException {
        return ticketManageService.getTicketById(ticketId);
    }

    @Operation(summary = "Смена статуса заявки",
            description = "Команда: изменяет статус заявки. Возвращает 204 без тела.")
    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @PutMapping("/{portalId}/tickets/{ticketId}/status")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> setTicketStatus(@PathVariable Long portalId, @PathVariable Long ticketId,
            @Valid @RequestBody TicketStatusRequest request)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.setTicketStatus(portalId, ticketId, request.status());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Смена приоритета заявки",
            description = "Команда: изменяет приоритет заявки. Возвращает 204 без тела.")
    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @PutMapping("/{portalId}/tickets/{ticketId}/priority")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> setTicketPriority(@PathVariable Long portalId, @PathVariable Long ticketId,
            @Valid @RequestBody TicketPriorityRequest request)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.setTicketPriority(portalId, ticketId, request.priority());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Удаление заявки", description = "Мягкое удаление заявки. Возвращает 204 без тела.")
    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @DeleteMapping("/{portalId}/tickets/{ticketId}")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> deleteTicket(@PathVariable Long portalId, @PathVariable Long ticketId)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.deleteTicket(ticketId, portalId);
        return ResponseEntity.noContent().build();
    }

}

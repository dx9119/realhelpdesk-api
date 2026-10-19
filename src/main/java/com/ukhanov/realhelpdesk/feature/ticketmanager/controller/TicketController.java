package com.ukhanov.realhelpdesk.feature.ticketmanager.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
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

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals")
public class TicketController {

    private final TicketManageService ticketManageService;

    @PostMapping("/{portalId}/tickets")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<CreateTicketResponse> createTicketForPortal(@Valid @RequestBody CreateTicketRequest request,
            @PathVariable Long portalId) throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        CreateTicketResponse response = ticketManageService.createTicket(request, portalId);
        return ResponseEntity.created(URI.create("/api/v1/portals/" + portalId + "/tickets/" + response.id())).body(response);
    }

    @GetMapping("/{portalId}/tickets")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    public PageResponse<TicketResponse> getPagedTicketsByPortalId(@PathVariable Long portalId,
            @RequestParam(required = false) TicketStatus status, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) throws TicketException, PortalException {

        if (status == null) {
            return ticketManageService.getPageTickets(portalId, page, size, sortBy, order);
        }

        Set<Long> ids = ticketManageService.getIdTicketWithStatus(portalId, status);
        return ticketManageService.getPageTicketsByIds(ids, page, size, sortBy, order);
    }

    @GetMapping("/{portalId}/tickets/ids")
    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    public ItemsResponse<Long> getTicketIds(@PathVariable Long portalId, @RequestParam(required = false) TicketStatus status,
            @RequestParam(defaultValue = "false") boolean noAnswer) {

        if (noAnswer) {
            return ItemsResponse.of(ticketManageService.getIdTicketNoAnswer(portalId));
        }
        if (status != null) {
            return ItemsResponse.of(ticketManageService.getIdTicketWithStatus(portalId, status));
        }
        return ItemsResponse.of(ticketManageService.getAllTickets(portalId).stream().map(TicketResponse::id).toList());
    }

    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    @GetMapping("/{portalId}/tickets/{ticketId}")
    public TicketResponse getTicketById(@PathVariable Long portalId, @PathVariable Long ticketId) throws TicketException {
        return ticketManageService.getTicketById(ticketId);
    }

    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @PutMapping("/{portalId}/tickets/{ticketId}/status")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> setTicketStatus(@PathVariable Long portalId, @PathVariable Long ticketId,
            @Valid @RequestBody TicketStatusRequest request)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.setTicketStatus(portalId, ticketId, request.status());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @PutMapping("/{portalId}/tickets/{ticketId}/priority")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> setTicketPriority(@PathVariable Long portalId, @PathVariable Long ticketId,
            @Valid @RequestBody TicketPriorityRequest request)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.setTicketPriority(portalId, ticketId, request.priority());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@ticketAccessValidationService.hasTicketChange(#portalId, #ticketId)")
    @DeleteMapping("/{portalId}/tickets/{ticketId}")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> deleteTicket(@PathVariable Long portalId, @PathVariable Long ticketId)
            throws TicketException, PortalException, MessagingException, UnsupportedEncodingException {
        ticketManageService.deleteTicket(ticketId, portalId);
        return ResponseEntity.noContent().build();
    }

}

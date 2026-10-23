package com.ukhanov.realhelpdesk.feature.messagemanager.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.MessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.messagemanager.service.MessageManageService;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals/{portalId}/tickets/{ticketId}/messages")
public class MessageController {

    private final MessageManageService messageManageService;

    @Operation(summary = "Создать сообщение в заявке",
            description = "Добавляет сообщение в ленту заявки. Location ведёт "
                    + "на существующий маршрут GET .../messages/{id}.")
    @PostMapping
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<CreateMessageResponse> createMessage(@Valid @RequestBody CreateMessageRequest request,
            @PathVariable Long portalId, @PathVariable Long ticketId)
            throws MessageException, MessagingException, TicketException, UnsupportedEncodingException {
        CreateMessageResponse response = messageManageService.createMessage(request, ticketId, portalId);
        return ResponseEntity
                .created(URI
                        .create("/api/v1/portals/" + portalId + "/tickets/" + ticketId + "/messages/" + response.id()))
                .body(response);
    }

    @Operation(summary = "Сообщения заявки", description = "Возвращает все сообщения заявки по возрастанию id.")
    @GetMapping
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    public ResponseEntity<ItemsResponse<MessageResponse>> getAllMessages(@PathVariable Long portalId,
            @PathVariable Long ticketId)
            throws MessageException, PortalException {
        List<MessageResponse> response = messageManageService.getAllMessage(ticketId);
        return ResponseEntity.ok(ItemsResponse.of(response));
    }

    @Operation(summary = "Сообщение по ID",
            description = "Возвращает одно сообщение заявки. Сообщение чужой заявки — 404.")
    @GetMapping("/{messageId}")
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable Long portalId, @PathVariable Long ticketId,
            @PathVariable Long messageId) throws MessageException {
        return ResponseEntity.ok(messageManageService.getMessage(ticketId, messageId));
    }

}

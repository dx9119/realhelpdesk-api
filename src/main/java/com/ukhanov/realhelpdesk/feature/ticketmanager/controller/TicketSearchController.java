package com.ukhanov.realhelpdesk.feature.ticketmanager.controller;

import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageCriteria;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketSearchCriteria;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.service.TicketSearchService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/api/v1/tickets")
public class TicketSearchController {

    private final TicketSearchService ticketSearchService;

    @Operation(summary = "Единый поиск по заявкам",
            description = "Фильтрация по строке, датам, статусу, приоритету и mine=true. "
                    + "mine=true заменяет прежний GET /tickets/mine.")
    @GetMapping
    public ResponseEntity<PageResponse<TicketResponse>> searchTickets(
            @Valid @ParameterObject @ModelAttribute TicketSearchCriteria criteria,
            @Valid @ParameterObject @ModelAttribute PageCriteria pageCriteria) {

        PageResponse<TicketResponse> results = ticketSearchService.searchTickets(criteria, pageCriteria);

        return ResponseEntity.ok(results);
    }
}

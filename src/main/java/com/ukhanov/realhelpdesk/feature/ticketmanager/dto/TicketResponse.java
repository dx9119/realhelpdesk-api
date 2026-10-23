package com.ukhanov.realhelpdesk.feature.ticketmanager.dto;

import java.time.Instant;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Единое представление заявки для всех эндпоинтов")
public record TicketResponse(Long id, String title, String body,
        @Schema(description = "authorId нужен UI, чтобы отличить свои заявки от чужих: "
                + "сравнивать по fullName нельзя (однофамильцы, смена имени).") Long authorId,
        String authorFullName, String portalName, Long portalId, TicketPriority ticketPriority,
        TicketStatus ticketStatus,
        TicketAccessStatus ticketAccessStatus, Instant createdAt,
        @Schema(description = "version — колонка @Version: конфликт при параллельном изменении "
                + "приходит как 409.") Integer version) {
}

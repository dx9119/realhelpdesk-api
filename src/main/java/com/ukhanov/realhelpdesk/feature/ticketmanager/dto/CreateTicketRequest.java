package com.ukhanov.realhelpdesk.feature.ticketmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;

public record CreateTicketRequest(
        @NotBlank(message = "Тема заявки не может быть пустой")
         @Size(max = 255, message = "Тема заявки не может превышать 255 символов")
         @NoHtml String title,
        @Size(max = 8000, message = "Сообщение не может быть больше 8000 символов") @NoHtml String body,
        TicketPriority ticketPriority,
        TicketAccessStatus ticketAccessStatus) {
}

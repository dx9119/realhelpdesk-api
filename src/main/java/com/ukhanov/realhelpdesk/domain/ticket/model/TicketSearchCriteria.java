package com.ukhanov.realhelpdesk.domain.ticket.model;

import java.time.Instant;

import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Фильтры единого поиска заявок.
 *
 * <p>
 * Живёт в domain-слое, потому что используется и контроллером, и {@code TicketSpecification}: feature-слой может
 * зависеть от domain, но не наоборот. Пагинация вынесена в отдельный {@code PageCriteria}, чтобы не смешивать фильтры и
 * параметры страницы.
 * </p>
 */
public record TicketSearchCriteria(
        @Size(max = 50, message = "Строка поиска не может превышать 50 символов") String search,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
        TicketStatus status,
        TicketPriority priority,
        Boolean mine) {

    public TicketSearchCriteria {
        mine = mine == null ? Boolean.FALSE : mine;
    }
}

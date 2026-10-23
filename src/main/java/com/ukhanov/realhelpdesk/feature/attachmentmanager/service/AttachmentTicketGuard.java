package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;

/**
 * Проверка, что заявка существует и принадлежит порталу из пути. Вынесена из сервиса вложений, чтобы один и тот же
 * инвариант не дублировался в upload/list/download.
 */
@Component
@RequiredArgsConstructor
public class AttachmentTicketGuard {

    private final TicketDomainService ticketDomainService;

    public void requireTicketInPortal(Long ticketId, Long portalId) throws TicketException {
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(portalId, "ID портала не должен быть null");
        TicketModel ticket = ticketDomainService.findTicketById(ticketId);
        if (ticket.getPortal() == null || !portalId.equals(ticket.getPortal().getId())) {
            throw TicketException.notFound("Заявка не найдена!");
        }
    }
}

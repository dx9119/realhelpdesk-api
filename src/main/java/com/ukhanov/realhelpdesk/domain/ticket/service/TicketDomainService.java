package com.ukhanov.realhelpdesk.domain.ticket.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class TicketDomainService {

    private final TicketRepository ticketRepository;

    @Transactional
    public TicketModel saveTicket(TicketModel ticketModel) {
        Objects.requireNonNull(ticketModel, "Заявка не должна быть null!");

        try {
            TicketModel savedTicket = ticketRepository.save(ticketModel);
            logger.debug("Заявка сохранена: {}", savedTicket.getId());
            return savedTicket;
        } catch (DataAccessException e) {
            // Ошибка логируется один раз на границе (GlobalExceptionHandler: ERROR со стектрейсом) — здесь только debug
            // для отладки.
            logger.debug("Не удалось сохранить заявку «{}»", ticketModel.getTitle(), e);
            throw new PersistenceException("Не удалось сохранить заявку", e);
        }
    }

    // Найти заявку по ID
    public TicketModel findTicketById(Long ticketId) throws TicketException {
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null!");

        TicketModel ticket = ticketRepository.findByIdAndTicketLiveStatus(ticketId, TicketLiveStatus.ACTIVE)
                .orElseThrow(() -> {
                    return TicketException.notFound("Заявка не найдена!");
                });

        return ticket;
    }

    public List<TicketModel> getTicketsByPortalId(Long portalId) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.debug("Получение всех заявок для портала с ID: {}", portalId);
        return ticketRepository.findAllByPortalIdAndTicketLiveStatus(portalId, TicketLiveStatus.ACTIVE);
    }

    public Page<TicketModel> getTicketsPageByPortalId(Long portalId, Pageable pageable) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.debug("Получение заявок с пагинацией для портала с ID: {}", portalId);
        return ticketRepository.findAllByPortalIdAndTicketLiveStatus(portalId, pageable, TicketLiveStatus.ACTIVE);
    }

    public Page<TicketModel> getTicketsPageByUserId(Long userId, Pageable pageable) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        logger.debug("Получение заявок с пагинацией для пользователя с ID: {}", userId);
        return ticketRepository.findAllByAuthorIdAndTicketLiveStatus(userId, TicketLiveStatus.ACTIVE, pageable);
    }

    public Page<TicketModel> getTicketsByIds(Set<Long> ids, Pageable pageable) {
        Objects.requireNonNull(ids, "ids не должны быть null");
        logger.debug("Получение заявок с пагинацией по ID: {}", ids);
        return ticketRepository.findByIdInAndTicketLiveStatus(ids, TicketLiveStatus.ACTIVE, pageable);
    }

    public Set<Long> getIdTicketWithNoAnswer(Long portalId) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.debug("Получение ID заявок без ответа для портала с ID: {}", portalId);
        return ticketRepository.findOpenTicketIdsWithoutMessagesByPortalIdAndLiveStatus(portalId,
                TicketLiveStatus.ACTIVE);
    }

    public Set<Long> getIdTicketWithStatus(Long portalId, TicketStatus status) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.debug("Получение ID заявок со статусом {} для портала с ID: {}", status, portalId);
        return ticketRepository.findTicketIdsByPortalIdAndStatusAndLiveStatus(portalId, status,
                TicketLiveStatus.ACTIVE);
    }

}

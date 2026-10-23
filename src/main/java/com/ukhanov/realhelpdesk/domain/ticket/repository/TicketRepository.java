package com.ukhanov.realhelpdesk.domain.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

@Repository
public interface TicketRepository extends JpaRepository<TicketModel, Long>, JpaSpecificationExecutor<TicketModel> {

    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    List<TicketModel> findAllByPortalIdAndTicketLiveStatus(@Param("portalId") Long portalId,
            TicketLiveStatus ticketLiveStatus);

    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    Page<TicketModel> findAllByPortalIdAndTicketLiveStatus(@Param("portalId") Long portalId, Pageable pageable,
            TicketLiveStatus ticketLiveStatus);

    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    Page<TicketModel> findAllByAuthorIdAndTicketLiveStatus(@Param("authorId") Long authorId,
            TicketLiveStatus ticketLiveStatus,
            Pageable pageable);

    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    Page<TicketModel> findByIdInAndTicketLiveStatus(Set<Long> ids, TicketLiveStatus ticketLiveStatus,
            Pageable pageable);

    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    Optional<TicketModel> findByIdAndTicketLiveStatus(Long id, TicketLiveStatus ticketLiveStatus);

    // Переопределение JpaSpecificationExecutor.findAll: страница тикетов по
    // фильтрам сразу с assignedUser/author/portal, без N+1 в ответе по каждой строке
    @Override
    @EntityGraph(attributePaths = {"assignedUser", "author", "portal"})
    Page<TicketModel> findAll(Specification<TicketModel> spec, Pageable pageable);

    @Query("""
                SELECT t.id FROM TicketModel t
                LEFT JOIN MessageModel m ON m.ticket = t
                WHERE t.portal.id = :portalId
                  AND m.id IS NULL
                  AND t.ticketStatus = com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus.OPEN
                  AND t.ticketLiveStatus = :liveStatus
            """)
    Set<Long> findOpenTicketIdsWithoutMessagesByPortalIdAndLiveStatus(@Param("portalId") Long portalId,
            @Param("liveStatus") com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus liveStatus);

    @Query("""
                SELECT t.id FROM TicketModel t
                WHERE t.portal.id = :portalId
                  AND t.ticketStatus = :status
                  AND t.ticketLiveStatus = :liveStatus
            """)
    Set<Long> findTicketIdsByPortalIdAndStatusAndLiveStatus(@Param("portalId") Long portalId,
            @Param("status") com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus status,
            @Param("liveStatus") com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus liveStatus);

    // Count для gauge tickets.open: обновление по расписанию раз в 30 секунд, не на scrape
    long countByTicketStatusAndTicketLiveStatus(TicketStatus ticketStatus, TicketLiveStatus ticketLiveStatus);

}

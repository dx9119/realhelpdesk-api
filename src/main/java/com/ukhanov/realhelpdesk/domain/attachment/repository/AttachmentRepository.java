package com.ukhanov.realhelpdesk.domain.attachment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;

@Repository
public interface AttachmentRepository extends JpaRepository<AttachmentModel, Long> {

    /**
     * Принадлежность цепочкой attachment → message → ticket → portal проверяется здесь же, в запросе: это даёт 404
     * вместо 403 и не подтверждает существование чужого файла. message и uploadedBy — fetch-join: метаданные ответа
     * читаются одним select'ом и без пробуждения ленивых связей вне транзакции (OSIV выключен). Мёртвая заявка
     * (ticketLiveStatus) файлов не отдаёт.
     */
    @Query("""
                SELECT a FROM AttachmentModel a
                JOIN FETCH a.message m
                JOIN FETCH a.uploadedBy u
                JOIN m.ticket t
                WHERE a.id = :attachmentId
                  AND t.id = :ticketId
                  AND t.portal.id = :portalId
                  AND t.ticketLiveStatus = :liveStatus
            """)
    Optional<AttachmentModel> findForTicket(@Param("attachmentId") Long attachmentId, @Param("ticketId") Long ticketId,
            @Param("portalId") Long portalId, @Param("liveStatus") TicketLiveStatus liveStatus);

    @Query("""
                SELECT a FROM AttachmentModel a
                JOIN FETCH a.message m
                JOIN FETCH a.uploadedBy u
                JOIN m.ticket t
                WHERE t.id = :ticketId
                  AND t.portal.id = :portalId
                  AND t.ticketLiveStatus = :liveStatus
                ORDER BY a.id
            """)
    List<AttachmentModel> findAllForTicket(@Param("ticketId") Long ticketId, @Param("portalId") Long portalId,
            @Param("liveStatus") TicketLiveStatus liveStatus);
}

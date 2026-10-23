package com.ukhanov.realhelpdesk.domain.attachment.service;

import java.util.List;
import java.util.Objects;

import jakarta.transaction.Transactional;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.domain.attachment.repository.AttachmentRepository;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AttachmentDomainService {

    private final AttachmentRepository attachmentRepository;

    public AttachmentDomainService(AttachmentRepository attachmentRepository) {
        this.attachmentRepository = Objects.requireNonNull(attachmentRepository,
                "attachmentRepository must not be null");
    }

    @Transactional
    public AttachmentModel saveAttachment(AttachmentModel attachment) {
        Objects.requireNonNull(attachment, "Вложение не должно быть null");

        return attachmentRepository.save(attachment);
    }

    /**
     * Вложение принадлежит заявке и заявка — порталу из запроса: связь проверяется в SQL (см. репозиторий), а не
     * загрузкой сущностей в память. Иначе чужой ID дал бы 403 и подтверждал бы, что файл существует.
     */
    public AttachmentModel getAttachmentForTicket(Long attachmentId, Long ticketId, Long portalId)
            throws AttachmentException {
        Objects.requireNonNull(attachmentId, "ID вложения не должен быть null");
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(portalId, "ID портала не должен быть null");

        return attachmentRepository.findForTicket(attachmentId, ticketId, portalId, TicketLiveStatus.ACTIVE)
                .orElseThrow(() -> {
                    logger.warn("Вложение {} не найдено в заявке {} портала {}", attachmentId, ticketId, portalId);
                    return AttachmentException.notFound("Файл не найден");
                });
    }

    public List<AttachmentModel> getAttachmentsForTicket(Long ticketId, Long portalId) {
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(portalId, "ID портала не должен быть null");

        return attachmentRepository.findAllForTicket(ticketId, portalId, TicketLiveStatus.ACTIVE);
    }
}

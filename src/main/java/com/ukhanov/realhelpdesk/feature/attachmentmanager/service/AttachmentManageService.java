package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Objects;

import jakarta.mail.MessagingException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.FileDownload;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Фасад вложений заявок: координирует хранилище, сообщение/строку вложения и чтение файла. Каждая зона ответственности
 * вынесена в отдельный сервис, поэтому у фасада пять зависимостей и нет «божественного» конструктора.
 *
 * <p>
 * Загрузка атомарна по отношению к сообщению: сначала файл в MinIO, потом БД; если запись в БД не удалась, объект в
 * MinIO удаляется — иначе в бакете остался бы осиротевший файл.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentManageService {

    private final AttachmentStorageService storageService;
    private final AttachmentMessageService messageService;
    private final AttachmentDownloadService downloadService;
    private final AttachmentTicketGuard ticketGuard;
    private final BusinessMetrics businessMetrics;

    public AttachmentResponse uploadAttachment(MultipartFile file, String messageText, Long portalId, Long ticketId)
            throws AttachmentException, TicketException, MessageException, MessagingException,
            UnsupportedEncodingException {
        Objects.requireNonNull(portalId, "ID портала не должен быть null");
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(file, "Файл не должен быть null");
        if (file.isEmpty()) {
            throw AttachmentException.badRequest("Файл не может быть пустым");
        }
        ticketGuard.requireTicketInPortal(ticketId, portalId);

        AttachmentStorageService.StoredAttachment stored = storageService.upload(file, ticketId);

        boolean attached = false;
        try {
            AttachmentResponse response = messageService.createMessageWithAttachment(stored, messageText, portalId,
                    ticketId);

            // инкремент после коммита saveAttachment: откат или исключение раньше счётчики не завышают
            businessMetrics.attachmentUploaded(stored.contentType(), stored.sizeBytes());

            logger.info("Файл «{}» ({} байт) прикреплён к заявке {}", LogSanitizer.fileName(stored.fileName()),
                    stored.sizeBytes(), ticketId);
            attached = true;
            return response;
        } finally {
            // finally вместо catch: осиротевший объект удаляется при любом сбое сохранения, включая RuntimeException
            if (!attached) {
                storageService.delete(stored.storageKey());
            }
        }
    }

    public List<AttachmentResponse> getAttachments(Long portalId, Long ticketId)
            throws AttachmentException, TicketException {
        ticketGuard.requireTicketInPortal(ticketId, portalId);
        return messageService.getAttachments(portalId, ticketId);
    }

    public FileDownload downloadAttachment(Long portalId, Long ticketId, Long attachmentId, String rangeHeader)
            throws AttachmentException {
        return downloadService.downloadAttachment(portalId, ticketId, attachmentId, rangeHeader);
    }
}

package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.io.InputStream;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.http.RangeHeader;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.domain.attachment.service.AttachmentDomainService;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.FileDownload;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;

import lombok.RequiredArgsConstructor;

/**
 * Чтение файла из MinIO: поток открывается здесь и живёт до закрытия контроллером. Диапазон разрешается по метаданным
 * из БД до открытия потока, поэтому при 416 соединение с хранилищем не устанавливается.
 */
@Service
@RequiredArgsConstructor
public class AttachmentDownloadService {

    private final AttachmentDomainService attachmentDomainService;
    private final AttachmentStorageService storageService;

    public FileDownload downloadAttachment(Long portalId, Long ticketId, Long attachmentId, String rangeHeader)
            throws AttachmentException {
        Objects.requireNonNull(portalId, "ID портала не должен быть null");
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(attachmentId, "ID вложения не должен быть null");

        AttachmentModel attachment = attachmentDomainService.getAttachmentForTicket(attachmentId, ticketId, portalId);
        RangeHeader.Parsed range = RangeHeader.parse(rangeHeader, attachment.getSizeBytes());

        InputStream content;
        if (range.kind() == RangeHeader.Kind.UNSATISFIABLE) {
            content = null;
        } else if (range.kind() == RangeHeader.Kind.PARTIAL) {
            content = storageService.download(attachment.getStorageKey(), range.start(), range.length());
        } else {
            content = storageService.download(attachment.getStorageKey());
        }
        return new FileDownload(attachment.getFileName(), attachment.getContentType(), attachment.getSizeBytes(), range,
                content);
    }
}

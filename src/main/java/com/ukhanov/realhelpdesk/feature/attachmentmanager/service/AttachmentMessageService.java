package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.io.UnsupportedEncodingException;
import java.util.List;

import jakarta.mail.MessagingException;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.domain.attachment.service.AttachmentDomainService;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.service.MessageDomainService;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.CreateAttachmentParam;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.messagemanager.service.MessageManageService;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;

/**
 * Доменная часть вложения: сообщение в заявке, строка {@code attachment} и сборка ответа API. Не работает с MinIO.
 */
@Service
@RequiredArgsConstructor
public class AttachmentMessageService {

    private static final String FILE_MESSAGE_PREFIX = "Файл: ";
    private static final int MAX_MESSAGE_LENGTH = 8000;

    private final MessageManageService messageManageService;
    private final MessageDomainService messageDomainService;
    private final AttachmentDomainService attachmentDomainService;
    private final AttachmentMappingService mappingService;
    private final CurrentUserProvider currentUserProvider;

    public AttachmentResponse createMessageWithAttachment(AttachmentStorageService.StoredAttachment stored,
            String messageText, Long portalId, Long ticketId)
            throws AttachmentException, MessageException, TicketException, MessagingException,
            UnsupportedEncodingException {
        CreateMessageRequest request = new CreateMessageRequest(resolveMessageText(messageText, stored.fileName()));
        CreateMessageResponse createdMessage = messageManageService.createMessage(request, ticketId, portalId);
        MessageModel message = messageDomainService.getMessageById(createdMessage.id());

        UserModel author = currentUserProvider.getCurrentUserModel();
        AttachmentModel attachment = attachmentDomainService.saveAttachment(mappingService.toEntity(
                new CreateAttachmentParam(message, author, stored.fileName(), stored.storageKey(), stored.contentType(),
                        stored.sizeBytes())));

        return mappingService.toResponse(attachment, portalId, ticketId);
    }

    public List<AttachmentResponse> getAttachments(Long portalId, Long ticketId) {
        return attachmentDomainService.getAttachmentsForTicket(ticketId, portalId)
                .stream()
                .map(attachment -> mappingService.toResponse(attachment, portalId, ticketId))
                .toList();
    }

    private String resolveMessageText(String messageText, String fileName) throws AttachmentException {
        String text = messageText == null || messageText.isBlank()
                ? FILE_MESSAGE_PREFIX + fileName
                : messageText.trim();
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw AttachmentException.badRequest("Сообщение не может быть больше " + MAX_MESSAGE_LENGTH + " символов");
        }
        return text;
    }
}

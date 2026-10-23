package com.ukhanov.realhelpdesk.domain.message.service;

import java.util.List;
import java.util.Objects;

import jakarta.transaction.Transactional;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.repository.MessageRepository;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MessageDomainService {

    private final MessageRepository messageRepository;

    public MessageDomainService(MessageRepository messageRepository) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
    }

    @Transactional
    public MessageModel saveMessage(MessageModel message) {
        Objects.requireNonNull(message, "Сообщение не должно быть null");

        return messageRepository.save(message);
    }

    public MessageModel getMessageById(Long messageId) throws MessageException {
        Objects.requireNonNull(messageId, "ID сообщения не должен быть null");

        return messageRepository.findById(messageId)
                .orElseThrow(() -> MessageException.notFound("Сообщение не найдено"));
    }

    public List<MessageModel> getMessagesByTicketId(Long ticketId) {
        Objects.requireNonNull(ticketId, "Идентификатор заявки не должен быть null");

        List<MessageModel> messages = messageRepository.findByTicketIdOrderByIdAsc(ticketId);
        Long firstId = messages.isEmpty() ? null : messages.get(0).getId();
        Long lastId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();

        logger.debug("Сообщения заявки {}: найдено={}, первый={}, последний={}", ticketId, messages.size(), firstId,
                lastId);

        return messages;
    }

    /** Количество сообщений заявки: нужно, чтобы поймать первый ответ до вставки нового сообщения. */
    public long countByTicketId(Long ticketId) {
        Objects.requireNonNull(ticketId, "Идентификатор заявки не должен быть null");

        return messageRepository.countByTicketId(ticketId);
    }

}

package com.ukhanov.realhelpdesk.feature.messagemanager.service;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.mail.MessagingException;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.service.MessageDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.MessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.messagemanager.mapper.MessageMapper;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.PublishNotificationCommand;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class MessageManageService {

    private final MessageMapper messageMapper;
    private final CurrentUserProvider currentUserProvider;
    private final TicketDomainService ticketDomainService;
    private final MessageDomainService messageDomainService;
    private final EmailDeliveryService emailDeliveryService;
    private final EmailTemplates emailTemplates;
    private final NotificationPublisher notificationPublisher;
    private final BusinessMetrics businessMetrics;

    public CreateMessageResponse createMessage(CreateMessageRequest request, Long ticketId, Long portalId)
            throws MessagingException, TicketException, UnsupportedEncodingException {
        Objects.requireNonNull(request, "Запрос на создание сообщения не должен быть null");
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");

        UserModel user = currentUserProvider.getCurrentUserModel();
        TicketModel ticket = ticketDomainService.findTicketById(ticketId);
        TicketStatus previousStatus = ticket.getTicketStatus();
        // до сохранения: первый ответ — в заявке ещё нет сообщений (доменное «без ответа» = без сообщений)
        boolean firstResponse = messageDomainService.countByTicketId(ticketId) == 0;

        ticket.setTicketStatus(TicketStatus.IN_PROGRESS);
        ticketDomainService.saveTicket(ticket);
        MessageModel message = messageDomainService.saveMessage(messageMapper.toEntity(request, user, ticket));

        // инкременты после коммита saveMessage: при откате транзакции метрика не завышается
        businessMetrics.statusTransition(previousStatus, TicketStatus.IN_PROGRESS);
        if (firstResponse) {
            businessMetrics.timeToFirstResponse(ticket.getCreatedAt());
        }

        // In-app оповещение автору заявки о новом сообщении (ответившего автора publisher исключает)
        notificationPublisher.publishToUsers(
                new PublishNotificationCommand(Set.of(ticket.getAuthor().getId()), NotificationEvent.NEW_MESSAGE,
                        user.getId(),
                        ticket.getId(), portalId, ticket.getTitle()));

        // Отправляем письмо с оповещением о новом сообщении в тикете
        emailDeliveryService.sendUserNotification(ticket.getAuthor().getEmail(),
                emailTemplates.ticketReplySubject(ticket.getId()),
                emailTemplates.ticketReplyBody(ticket.getId(), portalId), NotificationEvent.NEW_MESSAGE,
                ticket.getAuthor().getId());

        logger.info("Сообщение сохранено для заявки с ID: {}", ticketId);
        return new CreateMessageResponse(message.getId());
    }

    public List<MessageResponse> getAllMessage(Long ticketId) throws MessageException, PortalException {
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");

        List<MessageModel> messages = messageDomainService.getMessagesByTicketId(ticketId);
        logger.debug("Найдено {} сообщений для заявки с ID: {}", messages.size(), ticketId);

        return messages.stream().map(messageMapper::toResponse).toList();
    }

    /**
     * Одно сообщение заявки: нужно, чтобы Location после создания указывал на существующий маршрут. Сообщение другой
     * заявки не отдаём — иначе id из чужой ленты обходил бы проверку доступа к заявке.
     */
    public MessageResponse getMessage(Long ticketId, Long messageId) throws MessageException {
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        Objects.requireNonNull(messageId, "ID сообщения не должен быть null");

        MessageModel message = messageDomainService.getMessageById(messageId);
        if (message.getTicket() == null || !ticketId.equals(message.getTicket().getId())) {
            throw MessageException.notFound("Сообщение не найдено в этой заявке");
        }

        return messageMapper.toResponse(message);
    }

}

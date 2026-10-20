package com.ukhanov.realhelpdesk.feature.messagemanager.service;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.service.MessageDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.MessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.messagemanager.mapper.MessageMapper;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса MessageManageService")
class MessageManageServiceTest {

    @Mock
    private MessageMapper mockMessageMapper;
    @Mock
    private CurrentUserProvider mockCurrentUserProvider;
    @Mock
    private TicketDomainService mockTicketDomainService;
    @Mock
    private MessageDomainService mockMessageDomainService;
    @Mock
    private EmailDeliveryService mockEmailDeliveryService;
    @Mock
    private NotificationPublisher mockNotificationPublisher;

    @Captor
    private ArgumentCaptor<TicketModel> ticketCaptor;
    @Captor
    private ArgumentCaptor<MessageModel> messageCaptor;

    private MessageManageService service;

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final Long TICKET_ID = 42L;
    private static final Long PORTAL_ID = 100L;
    private static final String MESSAGE_TEXT = "Это тестовое сообщение от клиента";
    private static final String USER_EMAIL = "client@example.com";
    private static final Long USER_ID = 77L;

    @BeforeEach
    void setUp() {
        service = new MessageManageService(mockMessageMapper, mockCurrentUserProvider, mockTicketDomainService, mockMessageDomainService,
                mockEmailDeliveryService, emailTemplates, mockNotificationPublisher, new BusinessMetrics(meterRegistry));
    }

    // ────────────────────────────────────────────────
    // createMessage — успешный сценарий
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createMessage → создаёт сообщение, меняет статус тикета и отправляет уведомление")
    void createMessage_validRequest_createsMessageAndNotifies() throws Exception {

        // given
        // when
        CreateMessageRequest request = new CreateMessageRequest(MESSAGE_TEXT);

        UserModel currentUser = createUser(USER_EMAIL);
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(currentUser);

        TicketModel ticket = createTicket(TICKET_ID, TicketStatus.OPEN, currentUser);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);

        MessageModel messageToSave = createMessage(ticket, currentUser, MESSAGE_TEXT);
        when(mockMessageMapper.toEntity(eq(request), eq(currentUser), eq(ticket))).thenReturn(messageToSave);

        MessageModel savedMessage = createMessage(ticket, currentUser, MESSAGE_TEXT);
        savedMessage.setId(777L);
        when(mockMessageDomainService.saveMessage(any(MessageModel.class))).thenReturn(savedMessage);

        CreateMessageResponse response = service.createMessage(request, TICKET_ID, PORTAL_ID);

        // then
        assertThat(response.id()).isEqualTo(777L);

        // Проверяем изменение статуса тикета
        verify(mockTicketDomainService).saveTicket(ticketCaptor.capture());
        TicketModel savedTicket = ticketCaptor.getValue();
        assertThat(savedTicket.getTicketStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        // Проверяем вызов отправки email
        verify(mockEmailDeliveryService).sendUserNotification(eq(USER_EMAIL), eq(emailTemplates.ticketReplySubject(TICKET_ID)),
                eq(emailTemplates.ticketReplyBody(TICKET_ID, PORTAL_ID)), eq(NotificationEvent.NEW_MESSAGE), eq(USER_ID));

        // Счётчик сообщений до вставки — для метрики первого ответа
        verify(mockMessageDomainService).countByTicketId(TICKET_ID);

        verifyNoMoreInteractions(mockEmailDeliveryService, mockMessageDomainService, mockTicketDomainService);
    }

    // ────────────────────────────────────────────────
    // createMessage — бизнес-метрики
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createMessage → первый ответ пишет tickets.time.to.first.response, переход в IN_PROGRESS считается")
    void createMessage_openTicketFirstMessage_recordsFirstResponseMetric() throws Exception {

        // given
        // when
        CreateMessageRequest request = new CreateMessageRequest(MESSAGE_TEXT);

        UserModel currentUser = createUser(USER_EMAIL);
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(currentUser);

        TicketModel ticket = createTicket(TICKET_ID, TicketStatus.OPEN, currentUser);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);

        when(mockMessageMapper.toEntity(eq(request), eq(currentUser), eq(ticket)))
                .thenReturn(createMessage(ticket, currentUser, MESSAGE_TEXT));
        MessageModel savedMessage = createMessage(ticket, currentUser, MESSAGE_TEXT);
        savedMessage.setId(777L);
        when(mockMessageDomainService.saveMessage(any(MessageModel.class))).thenReturn(savedMessage);
        when(mockMessageDomainService.countByTicketId(TICKET_ID)).thenReturn(0L);

        service.createMessage(request, TICKET_ID, PORTAL_ID);

        Timer firstResponse = meterRegistry.find("tickets.time.to.first.response").timer();
        // then
        assertThat(firstResponse).isNotNull();
        assertThat(firstResponse.count()).isEqualTo(1);

        Counter transition = meterRegistry.find("tickets.status.transitions").tags("from", "OPEN", "to", "IN_PROGRESS").counter();
        assertThat(transition).isNotNull();
        assertThat(transition.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("createMessage → второе сообщение не завышает время первого ответа и не считает переход")
    void createMessage_ticketInProgressSecondMessage_skipsFirstResponseMetric() throws Exception {

        // given: в заявке уже есть сообщения и статус уже IN_PROGRESS
        // when
        CreateMessageRequest request = new CreateMessageRequest(MESSAGE_TEXT);

        UserModel currentUser = createUser(USER_EMAIL);
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(currentUser);

        TicketModel ticket = createTicket(TICKET_ID, TicketStatus.IN_PROGRESS, currentUser);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);

        when(mockMessageMapper.toEntity(eq(request), eq(currentUser), eq(ticket)))
                .thenReturn(createMessage(ticket, currentUser, MESSAGE_TEXT));
        MessageModel savedMessage = createMessage(ticket, currentUser, MESSAGE_TEXT);
        savedMessage.setId(778L);
        when(mockMessageDomainService.saveMessage(any(MessageModel.class))).thenReturn(savedMessage);
        when(mockMessageDomainService.countByTicketId(TICKET_ID)).thenReturn(1L);

        service.createMessage(request, TICKET_ID, PORTAL_ID);

        // then
        assertThat(meterRegistry.find("tickets.time.to.first.response").timer()).isNull();
        assertThat(meterRegistry.find("tickets.status.transitions").counter()).isNull();
    }

    // ────────────────────────────────────────────────
    // createMessage — исключения
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createMessage → null request → NPE")
    void createMessage_nullRequest_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.createMessage(null, TICKET_ID, PORTAL_ID)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Запрос на создание сообщения не должен быть null");
    }

    @Test
    @DisplayName("createMessage → тикет не найден → TicketException")
    void createMessage_ticketNotFound_throwsTicketException() throws TicketException {

        // given
        // when
        CreateMessageRequest request = new CreateMessageRequest(MESSAGE_TEXT);

        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(createUser(USER_EMAIL));
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenThrow(new TicketException("Тикет не найден"));

        // then
        assertThatThrownBy(() -> service.createMessage(request, TICKET_ID, PORTAL_ID)).isInstanceOf(TicketException.class);
    }

    // ────────────────────────────────────────────────
    // getAllMessage
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getAllMessage → возвращает список сообщений")
    void getAllMessage_existingMessages_returnsMappedResponses() throws MessageException, PortalException {

        // given
        // when
        MessageModel msg1 = createMessage(null, null, "Сообщение 1");
        msg1.setId(1L);
        MessageModel msg2 = createMessage(null, null, "Сообщение 2");
        msg2.setId(2L);

        when(mockMessageDomainService.getMessagesByTicketId(TICKET_ID)).thenReturn(List.of(msg1, msg2));

        MessageResponse resp1 = new MessageResponse(1L, "Сообщение 1", null, null, null, null);
        MessageResponse resp2 = new MessageResponse(2L, "Сообщение 2", null, null, null, null);

        when(mockMessageMapper.toResponse(msg1)).thenReturn(resp1);
        when(mockMessageMapper.toResponse(msg2)).thenReturn(resp2);

        List<MessageResponse> result = service.getAllMessage(TICKET_ID);

        // then
        assertThat(result).hasSize(2).extracting(MessageResponse::messageText).containsExactly("Сообщение 1", "Сообщение 2");
    }

    @Test
    @DisplayName("getAllMessage → null ticketId → NPE")
    void getAllMessage_nullTicketId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.getAllMessage(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID заявки не должен быть null");
    }

    @Test
    @DisplayName("getAllMessage → пустой список сообщений → возвращает пустой список")
    void getAllMessage_noMessages_returnsEmptyList() throws MessageException, PortalException {

        // given
        // when
        when(mockMessageDomainService.getMessagesByTicketId(TICKET_ID)).thenReturn(List.of());

        List<MessageResponse> result = service.getAllMessage(TICKET_ID);

        // then
        assertThat(result).isEmpty();
    }

    // ────────────────────────────────────────────────
    // Вспомогательные фабрики
    // ────────────────────────────────────────────────

    private UserModel createUser(String email) {
        UserModel u = new UserModel();
        u.setEmail(email);
        u.setId(USER_ID);
        return u;
    }

    private TicketModel createTicket(Long id, TicketStatus status, UserModel author) {
        TicketModel t = new TicketModel();
        t.setId(id);
        t.setTitle("Тестовый тикет");
        t.setBody("Описание");
        t.setAuthor(author);
        t.setTicketStatus(status);
        t.setTicketLiveStatus(TicketLiveStatus.ACTIVE);
        t.setAccessStatus(TicketAccessStatus.CREATOR_AND_PORTAL_USERS);
        t.setCreatedAt(Instant.now());
        return t;
    }

    private MessageModel createMessage(TicketModel ticket, UserModel author, String text) {
        MessageModel m = new MessageModel();
        m.setTicket(ticket);
        m.setAuthor(author);
        m.setMessageText(text);
        m.setCreatedAt(Instant.now());
        return m;
    }
}

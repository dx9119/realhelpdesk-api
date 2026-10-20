package com.ukhanov.realhelpdesk.domain.message.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.repository.MessageRepository;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса MessageDomainService")
class MessageDomainServiceTest {

    @Mock
    private MessageRepository mockMessageRepository;

    private MessageDomainService service;

    private static final Long TICKET_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new MessageDomainService(mockMessageRepository);
    }

    // ────────────────────────────────────────────────
    // saveMessage
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("saveMessage → должен вернуть сущность с присвоенным id")
    void saveMessage_validMessage_returnsEntityWithGeneratedId() {

        // given
        // when
        TicketModel ticket = createTicket(TICKET_ID);

        MessageModel messageToSave = createMessage(ticket, "Не могу войти в аккаунт");
        // ← здесь id == null — и это правильно!

        // Объект, который симулирует, что вернул репозиторий после сохранения
        MessageModel savedFromRepo = createMessage(ticket, "Не могу войти в аккаунт");
        savedFromRepo.setId(1007L); // ← только здесь можно setId
        savedFromRepo.setCreatedAt(Instant.now()); // имитируем @PrePersist

        when(mockMessageRepository.save(any(MessageModel.class))).thenReturn(savedFromRepo);

        MessageModel result = service.saveMessage(messageToSave);

        // then
        assertThat(result.getId()).isEqualTo(1007L);
        assertThat(result.getMessageText()).isEqualTo("Не могу войти в аккаунт");
        assertThat(result.getTicket().getId()).isEqualTo(TICKET_ID);
        assertThat(result.getCreatedAt()).isNotNull();

        // Проверяем, что в репозиторий попал объект без id
        verify(mockMessageRepository).save(messageToSave);
        assertThat(messageToSave.getId()).as("id должен оставаться null до сохранения").isNull();
    }

    @Test
    @DisplayName("saveMessage → кидает NPE при null")
    void saveMessage_null_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.saveMessage(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Сообщение не должно быть null");
    }

    // ────────────────────────────────────────────────
    // getMessagesByTicketId
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getMessagesByTicketId → возвращает список сообщений")
    void getMessagesByTicketId_existingMessages_returnsAllInOrder() {

        // given
        // when
        TicketModel ticket = createTicket(TICKET_ID);

        MessageModel m1 = createMessage(ticket, "Клиент: проблема с оплатой");
        MessageModel m2 = createMessage(ticket, "Поддержка: проверьте, пожалуйста, номер карты");

        List<MessageModel> expected = List.of(m1, m2);

        when(mockMessageRepository.findByTicketIdOrderByIdAsc(TICKET_ID)).thenReturn(expected);

        List<MessageModel> result = service.getMessagesByTicketId(TICKET_ID);

        // then
        assertThat(result).hasSize(2).usingRecursiveFieldByFieldElementComparatorIgnoringFields("createdAt", "version").isEqualTo(expected);

        assertThat(result.get(0).getMessageText()).contains("оплатой");
        assertThat(result.get(1).getTicket()).isSameAs(ticket);

        verify(mockMessageRepository).findByTicketIdOrderByIdAsc(TICKET_ID);
        verifyNoMoreInteractions(mockMessageRepository);
    }

    @Test
    @DisplayName("getMessagesByTicketId → пустой список когда сообщений нет")
    void getMessagesByTicketId_noMessages_returnsEmpty() {

        // given
        // when
        when(mockMessageRepository.findByTicketIdOrderByIdAsc(TICKET_ID)).thenReturn(Collections.emptyList());

        List<MessageModel> result = service.getMessagesByTicketId(TICKET_ID);

        // then
        assertThat(result).isEmpty();

        verify(mockMessageRepository).findByTicketIdOrderByIdAsc(TICKET_ID);
    }

    @Test
    @DisplayName("getMessagesByTicketId → null ticketId → NPE")
    void getMessagesByTicketId_null_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.getMessagesByTicketId(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Идентификатор заявки не должен быть null");
    }

    // ────────────────────────────────────────────────
    // Фабрики
    // ────────────────────────────────────────────────

    private TicketModel createTicket(Long id) {
        TicketModel t = new TicketModel();
        t.setId(id);
        t.setTitle("Тикет #" + id);
        t.setTicketLiveStatus(TicketLiveStatus.ACTIVE);
        t.setAccessStatus(TicketAccessStatus.ALL_USERS);
        return t;
    }

    private MessageModel createMessage(TicketModel ticket, String text) {
        MessageModel m = new MessageModel();
        m.setTicket(ticket);
        m.setMessageText(text);
        return m;
    }
}

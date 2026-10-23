package com.ukhanov.realhelpdesk.domain.ticket.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса TicketDomainService")
class TicketDomainServiceTest {

    private static final Long TICKET_ID = 123L;
    private static final Long PORTAL_ID = 456L;
    private static final Long USER_ID = 77L;

    @Mock
    private TicketRepository mockTicketRepository;

    private TicketDomainService service;

    @BeforeEach
    void setUp() {
        service = new TicketDomainService(mockTicketRepository);
    }

    // ────────────────────────────────────────────────
    // saveTicket
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("saveTicket → сохраняет заявку и возвращает сохранённую сущность")
    void saveTicket_validTicket_returnsPersistedTicket() {

        // given
        // when
        TicketModel ticketToSave = createTicket(null, "Проблема с доступом", "Не могу войти");
        TicketModel savedTicket = createTicket(TICKET_ID, "Проблема с доступом", "Не могу войти");
        savedTicket.setCreatedAt(Instant.now());
        when(mockTicketRepository.save(any(TicketModel.class))).thenReturn(savedTicket);

        TicketModel result = service.saveTicket(ticketToSave);

        // then
        assertThat(result).usingRecursiveComparison().isEqualTo(savedTicket);
        assertThat(result.getId()).isEqualTo(TICKET_ID);
        assertThat(result.getCreatedAt()).isNotNull();
        verify(mockTicketRepository).save(ticketToSave);
        verifyNoMoreInteractions(mockTicketRepository);
    }

    @Test
    @DisplayName("saveTicket → кидает NPE при null")
    void saveTicket_nullTicket_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.saveTicket(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Заявка не должна быть null");
    }

    @Test
    @DisplayName("saveTicket → кидает PersistenceException при ошибке сохранения")
    void saveTicket_repositoryThrows_wrapsInPersistenceException() {

        // given
        // when
        TicketModel ticket = createTicket(null, "Тест", "Описание");
        when(mockTicketRepository.save(any())).thenThrow(new DataAccessResourceFailureException("DB error"));

        // then
        assertThatThrownBy(() -> service.saveTicket(ticket)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Не удалось сохранить заявку")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ────────────────────────────────────────────────
    // findTicketById
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("findTicketById → возвращает активную заявку по ID")
    void findTicketById_activeTicket_returnsIt() throws TicketException {

        // given
        // when
        TicketModel expected = createTicket(TICKET_ID, "Тестовая заявка", "Описание");
        when(mockTicketRepository.findByIdAndTicketLiveStatus(TICKET_ID, TicketLiveStatus.ACTIVE))
                .thenReturn(Optional.of(expected));

        TicketModel result = service.findTicketById(TICKET_ID);

        // then
        assertThat(result).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    @DisplayName("findTicketById → кидает TicketException если заявка не найдена или не активна")
    void findTicketById_notFoundOrInactive_throwsTicketException() {

        // given
        // when
        when(mockTicketRepository.findByIdAndTicketLiveStatus(TICKET_ID, TicketLiveStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // then
        assertThatThrownBy(() -> service.findTicketById(TICKET_ID)).isInstanceOf(TicketException.class)
                .hasMessageContaining("Заявка не найдена");
    }

    @Test
    @DisplayName("findTicketById → null ID → NPE")
    void findTicketById_nullId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.findTicketById(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID заявки не должен быть null");
    }

    // ────────────────────────────────────────────────
    // getTicketsByPortalId
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getTicketsByPortalId → возвращает активные заявки портала")
    void getTicketsByPortalId_activeTickets_returnsThem() {

        // given
        // when
        TicketModel first = createTicket(1L, "Заявка 1", "Текст 1");
        TicketModel second = createTicket(2L, "Заявка 2", "Текст 2");
        when(mockTicketRepository.findAllByPortalIdAndTicketLiveStatus(PORTAL_ID, TicketLiveStatus.ACTIVE))
                .thenReturn(List.of(first, second));

        List<TicketModel> result = service.getTicketsByPortalId(PORTAL_ID);

        // then
        assertThat(result).hasSize(2)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("getTicketsByPortalId → null portalId → NPE")
    void getTicketsByPortalId_nullPortalId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.getTicketsByPortalId(null)).isInstanceOf(NullPointerException.class);
    }

    // ────────────────────────────────────────────────
    // getTicketsPageByPortalId / getTicketsPageByUserId / getTicketsByIds
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getTicketsPageByPortalId → возвращает страницу активных заявок")
    void getTicketsPageByPortalId_activeTickets_returnsPage() {

        // given
        // when
        Pageable pageable = PageRequest.of(0, 10);
        TicketModel ticket = createTicket(TICKET_ID, "Тест", "Описание");
        Page<TicketModel> page = new PageImpl<>(List.of(ticket), pageable, 1);
        when(mockTicketRepository.findAllByPortalIdAndTicketLiveStatus(eq(PORTAL_ID), eq(pageable),
                eq(TicketLiveStatus.ACTIVE)))
                .thenReturn(page);

        Page<TicketModel> result = service.getTicketsPageByPortalId(PORTAL_ID, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getTicketsPageByUserId → возвращает страницу заявок автора")
    void getTicketsPageByUserId_authorWithoutTickets_returnsEmptyPage() {

        // given
        // when
        Pageable pageable = PageRequest.of(0, 20);
        Page<TicketModel> page = new PageImpl<>(Collections.emptyList(), pageable, 0);
        when(mockTicketRepository.findAllByAuthorIdAndTicketLiveStatus(eq(USER_ID), eq(TicketLiveStatus.ACTIVE),
                eq(pageable)))
                .thenReturn(page);

        Page<TicketModel> result = service.getTicketsPageByUserId(USER_ID, pageable);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getTicketsByIds → возвращает страницу по списку ID")
    void getTicketsByIds_knownIds_returnsPage() {

        // given
        // when
        Set<Long> ids = Set.of(10L, 20L, 30L);
        Pageable pageable = PageRequest.of(0, 5);
        when(mockTicketRepository.findByIdInAndTicketLiveStatus(eq(ids), eq(TicketLiveStatus.ACTIVE), eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        Page<TicketModel> result = service.getTicketsByIds(ids, pageable);

        // then
        assertThat(result).isEmpty();
    }

    // ────────────────────────────────────────────────
    // getIdTicketWithNoAnswer / getIdTicketWithStatus
    // ────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("getIdTicketWithStatus → возвращает ID заявок с заданным статусом")
    void getIdTicketWithStatus_anyStatus_returnsMatchingIds(TicketStatus status) {

        // given
        // when
        Set<Long> expectedIds = Set.of(100L, 200L);
        when(mockTicketRepository.findTicketIdsByPortalIdAndStatusAndLiveStatus(PORTAL_ID, status,
                TicketLiveStatus.ACTIVE))
                .thenReturn(expectedIds);

        Set<Long> result = service.getIdTicketWithStatus(PORTAL_ID, status);

        // then
        assertThat(result).isEqualTo(expectedIds);
    }

    @Test
    @DisplayName("getIdTicketWithNoAnswer → возвращает ID открытых заявок без ответов")
    void getIdTicketWithNoAnswer_openTicketsWithoutMessages_returnsIds() {

        // given
        // when
        Set<Long> expected = Set.of(500L, 501L);
        when(mockTicketRepository.findOpenTicketIdsWithoutMessagesByPortalIdAndLiveStatus(PORTAL_ID,
                TicketLiveStatus.ACTIVE))
                .thenReturn(expected);

        Set<Long> result = service.getIdTicketWithNoAnswer(PORTAL_ID);

        // then
        assertThat(result).isEqualTo(expected);
    }

    // ────────────────────────────────────────────────
    // Фабрики
    // ────────────────────────────────────────────────

    private TicketModel createTicket(Long id, String title, String body) {
        TicketModel t = new TicketModel();
        t.setId(id);
        t.setTitle(title);
        t.setBody(body);
        t.setTicketLiveStatus(TicketLiveStatus.ACTIVE);
        t.setTicketStatus(TicketStatus.OPEN);
        t.setAccessStatus(TicketAccessStatus.CREATOR_AND_PORTAL_USERS);
        t.setCreatedAt(Instant.now());
        return t;
    }
}

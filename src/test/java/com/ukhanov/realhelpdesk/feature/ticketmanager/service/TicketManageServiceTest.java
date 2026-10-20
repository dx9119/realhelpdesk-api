package com.ukhanov.realhelpdesk.feature.ticketmanager.service;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.TicketAccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.mapper.TicketMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Тесты сервиса TicketManageService")
class TicketManageServiceTest {

    @Mock
    private TicketDomainService mockTicketDomainService;
    @Mock
    private CurrentUserProvider mockCurrentUserProvider;
    @Mock
    private PortalDomainService mockPortalDomainService;
    @Mock
    private PaginationAdapter mockPaginationAdapter;
    @Mock
    private EmailDeliveryService mockEmailDeliveryService;
    @Mock
    private TicketAccessValidationService mockTicketAccessValidationService;
    @Mock
    private TicketRepository mockTicketRepository;
    @Mock
    private NotificationPublisher mockNotificationPublisher;

    @Captor
    private ArgumentCaptor<TicketModel> ticketCaptor;

    private TicketManageService service;

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final Long TICKET_ID = 42L;
    private static final Long PORTAL_ID = 100L;
    private static final Long USER_ID = 77L;
    private static final Pageable DEFAULT_PAGEABLE = PageRequest.of(0, 10);

    @BeforeEach
    void setUp() {
        service = new TicketManageService(mockTicketDomainService, mockCurrentUserProvider, mockPortalDomainService, mockPaginationAdapter,
                mockEmailDeliveryService, mockTicketAccessValidationService, mockTicketRepository, emailTemplates,
                mockNotificationPublisher, Mappers.getMapper(TicketMapper.class), ObservationRegistry.NOOP,
                new BusinessMetrics(meterRegistry));

        UserModel currentUser = new UserModel();
        currentUser.setId(USER_ID);
        currentUser.setEmail("test@user.com");
        lenient().when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(currentUser);
    }

    // ────────────────────────────────────────────────
    // getTicketById
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getTicketById → успех")
    void getTicketById_existingTicket_returnsTicketResponse() throws TicketException {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);

        TicketResponse response = service.getTicketById(TICKET_ID);

        // then
        assertThat(response).isNotNull();
        verify(mockTicketDomainService).findTicketById(TICKET_ID);
    }

    @Test
    @DisplayName("getTicketById → null id → NPE")
    void getTicketById_nullId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.getTicketById(null)).isInstanceOf(NullPointerException.class);
    }

    // ────────────────────────────────────────────────
    // createTicket
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createTicket → успех")
    void createTicket_validRequest_returnsCreatedTicketId() throws Exception {

        // given
        // when
        CreateTicketRequest request = new CreateTicketRequest("New Issue", null, null, null);

        PortalModel portal = defaultPortal(PORTAL_ID);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);

        TicketModel saved = defaultTicket(TICKET_ID);
        when(mockTicketDomainService.saveTicket(any())).thenReturn(saved);

        CreateTicketResponse response = service.createTicket(request, PORTAL_ID);

        // then
        assertThat(response.id()).isEqualTo(TICKET_ID);

        verify(mockTicketDomainService).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getTitle()).isEqualTo("New Issue");

        verify(mockEmailDeliveryService).initNotifyPortalUsers(eq(portal), anyString(), anyString(), eq(NotificationEvent.NEW_TICKET));
    }

    // ────────────────────────────────────────────────
    // setTicketStatus / setTicketPriority / deleteTicket
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("setTicketStatus → письмо об изменении статуса участникам портала")
    void setTicketStatus_withRights_sendsChangeNotification() throws Exception {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        PortalModel portal = defaultPortal(PORTAL_ID);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(true);
        when(mockTicketDomainService.saveTicket(any())).thenReturn(ticket);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);

        service.setTicketStatus(PORTAL_ID, TICKET_ID, TicketStatus.CLOSED);

        // then
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.CLOSED);
        verify(mockEmailDeliveryService).initNotifyPortalUsers(eq(portal),
                eq(emailTemplates.updateStatusTicketSubject(TICKET_ID, TicketStatus.CLOSED)),
                eq(emailTemplates.updateStatusTicketBody(TICKET_ID, PORTAL_ID)), eq(NotificationEvent.CHANGE_TICKET));
    }

    @Test
    @DisplayName("setTicketStatus без прав → TicketException и письма нет")
    void setTicketStatus_withoutRights_sendsNoEmail() throws TicketException, PortalException {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(false);

        // then
        assertThatThrownBy(() -> service.setTicketStatus(PORTAL_ID, TICKET_ID, TicketStatus.CLOSED)).isInstanceOf(TicketException.class);

        verifyNoInteractions(mockEmailDeliveryService);
    }

    @Test
    @DisplayName("setTicketPriority → письмо об изменении приоритета участникам портала")
    void setTicketPriority_withRights_sendsChangeNotification() throws Exception {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        PortalModel portal = defaultPortal(PORTAL_ID);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(true);
        when(mockTicketDomainService.saveTicket(any())).thenReturn(ticket);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);

        service.setTicketPriority(PORTAL_ID, TICKET_ID, TicketPriority.HIGH);

        // then
        assertThat(ticket.getTicketPriority()).isEqualTo(TicketPriority.HIGH);
        verify(mockEmailDeliveryService).initNotifyPortalUsers(eq(portal),
                eq(emailTemplates.updatePriorityTicketSubject(TICKET_ID, TicketPriority.HIGH)),
                eq(emailTemplates.updatePriorityTicketBody(TICKET_ID, PORTAL_ID)), eq(NotificationEvent.CHANGE_TICKET));
    }

    @Test
    @DisplayName("deleteTicket → письмо об удалении участникам портала")
    void deleteTicket_withRights_sendsDeletedNotification() throws Exception {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        PortalModel portal = defaultPortal(PORTAL_ID);
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(true);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);
        when(mockTicketRepository.save(any())).thenReturn(ticket);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);

        service.deleteTicket(TICKET_ID, PORTAL_ID);

        // then
        assertThat(ticket.getTicketLiveStatus()).isEqualTo(TicketLiveStatus.DELETE);
        verify(mockEmailDeliveryService).initNotifyPortalUsers(eq(portal), eq(emailTemplates.deletedTicketSubject(TICKET_ID)),
                eq(emailTemplates.deletedTicketBody(TICKET_ID, "test@user.com")), eq(NotificationEvent.TICKET_DELETED));
    }

    @Test
    @DisplayName("deleteTicket без прав владельца → TicketException и письма нет")
    void deleteTicket_withoutRights_sendsNoEmail() throws TicketException, PortalException {

        // given
        // when
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(false);

        // then
        assertThatThrownBy(() -> service.deleteTicket(TICKET_ID, PORTAL_ID)).isInstanceOf(TicketException.class);

        verifyNoInteractions(mockEmailDeliveryService);
    }

    // ────────────────────────────────────────────────
    // getPageTickets
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getPageTickets → успех")
    void getPageTickets_validParams_returnsMappedPage() throws TicketException, PortalException {

        // given
        // when
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(mockPaginationAdapter.buildPageRequest(eq(0), eq(10), eq("title"), eq("asc"), any())).thenReturn(pageRequest);

        TicketModel ticketModel = defaultTicket(1L);
        Page<TicketModel> ticketPage = new PageImpl<>(List.of(ticketModel), pageRequest, 1);
        when(mockTicketDomainService.getTicketsPageByPortalId(PORTAL_ID, pageRequest)).thenReturn(ticketPage);

        // Создаём маппинг вручную
        TicketResponse dto = new TicketResponse(1L, "Test Ticket", null, null, null, null, null, null, null, null, null, null);

        Page<TicketResponse> mappedPage = ticketPage.map(t -> dto); // имитируем map

        PageResponse<TicketResponse> expected = new PageResponse.Builder<TicketResponse>().page(0).size(10).totalElements(1L).totalPages(1)
                .content(List.of(dto)).build();

        // Стабим mapToResponse с mappedPage (имитируем, что map уже прошёл)
        when(mockPaginationAdapter.<TicketResponse>mapToResponse(any(), eq("title"), eq("asc"))).thenReturn(expected);

        PageResponse<TicketResponse> result = service.getPageTickets(PORTAL_ID, 0, 10, "title", "asc");

        // then
        assertThat(result).isNotNull();
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
    }

    // ────────────────────────────────────────────────
    // Бизнес-метрики
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createTicket → tickets.created с тегами priority и access из enum'ов заявки")
    void createTicket_newTicket_incrementsTicketsCreatedMetric() throws Exception {

        // given
        // when
        CreateTicketRequest request = new CreateTicketRequest("New Issue", null, null, null);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(defaultPortal(PORTAL_ID));
        when(mockTicketDomainService.saveTicket(any())).thenReturn(defaultTicket(TICKET_ID));

        service.createTicket(request, PORTAL_ID);

        Counter created = meterRegistry.find("tickets.created").tags("priority", "MEDIUM", "access", "CREATOR_AND_PORTAL_USERS").counter();
        // then
        assertThat(created).isNotNull();
        assertThat(created.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("setTicketStatus → переход в CLOSED считает transitions и таймер времени решения")
    void setTicketStatus_toClosed_countsTransitionAndResolutionTimer() throws Exception {

        // given
        // when
        TicketModel ticket = defaultTicket(TICKET_ID);
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket);
        when(mockTicketAccessValidationService.hasTicketChange(PORTAL_ID, TICKET_ID)).thenReturn(true);
        when(mockTicketDomainService.saveTicket(any())).thenReturn(ticket);
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(defaultPortal(PORTAL_ID));

        service.setTicketStatus(PORTAL_ID, TICKET_ID, TicketStatus.CLOSED);

        Counter transition = meterRegistry.find("tickets.status.transitions").tags("from", "OPEN", "to", "CLOSED").counter();
        // then
        assertThat(transition).isNotNull();
        assertThat(transition.count()).isEqualTo(1);

        Timer resolution = meterRegistry.find("tickets.time.to.resolution").timer();
        assertThat(resolution).isNotNull();
        assertThat(resolution.count()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────
    // Фабрики
    // ────────────────────────────────────────────────

    private TicketModel defaultTicket(Long id) {
        TicketModel t = new TicketModel();
        t.setId(id);
        t.setTitle("Test Ticket");
        t.setBody("Description");
        t.setTicketStatus(TicketStatus.OPEN);
        t.setTicketPriority(TicketPriority.MEDIUM);
        t.setTicketLiveStatus(TicketLiveStatus.ACTIVE);
        t.setCreatedAt(Instant.now());

        UserModel author = new UserModel();
        author.setId(USER_ID);
        t.setAuthor(author);

        t.setPortal(defaultPortal(PORTAL_ID));

        return t;
    }

    private PortalModel defaultPortal(Long id) {
        PortalModel p = new PortalModel();
        p.setId(id);
        p.setName("Test Portal");
        return p;
    }
}

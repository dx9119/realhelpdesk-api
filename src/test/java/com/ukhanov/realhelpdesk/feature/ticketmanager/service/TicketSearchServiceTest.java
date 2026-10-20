package com.ukhanov.realhelpdesk.feature.ticketmanager.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.exception.PaginationException;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.mapper.TicketMapperImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Тесты сервиса TicketSearchService")
class TicketSearchServiceTest {

    @Mock
    private TicketRepository mockTicketRepository;
    @Mock
    private PortalRepository mockPortalRepository;
    @Mock
    private CurrentUserProvider mockCurrentUserProvider;

    @Captor
    private ArgumentCaptor<Specification<TicketModel>> specCaptor;

    private TicketSearchService service;

    private static final Long USER_ID = 77L;
    private static final Instant START_DATE = LocalDateTime.of(2025, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
    private static final Instant END_DATE = LocalDateTime.of(2025, 12, 31, 23, 59).toInstant(ZoneOffset.UTC);
    /** Значения по умолчанию эндпоинта: page=0, size=10, sortBy=createdAt, order=desc. */
    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10, Sort.by("createdAt").descending());

    @BeforeEach
    void setUp() {
        service = new TicketSearchService(mockTicketRepository, mockPortalRepository, mockCurrentUserProvider, new TicketMapperImpl(),
                new PaginationAdapter());

        lenient().when(mockCurrentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    // ────────────────────────────────────────────────
    // Основной сценарий поиска
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("searchTickets → успех: находит тикеты по всем фильтрам")
    void searchTickets_allFiltersProvided_returnsMatchingPage() {

        // given
        // when
        List<PortalModel> owned = List.of(createPortal(1L));
        List<PortalModel> allowed = List.of(createPortal(2L));
        List<Long> publicIds = List.of(3L);

        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(owned);
        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(allowed);
        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(publicIds);

        TicketModel ticket = new TicketModel();
        ticket.setId(100L);
        Page<TicketModel> page = new PageImpl<>(List.of(ticket), DEFAULT_PAGE, 1);

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE))).thenReturn(page);

        PageResponse<TicketResponse> result = service.searchTickets("test search", START_DATE, END_DATE, TicketStatus.OPEN,
                TicketPriority.HIGH, true, 0, 10, "createdAt", "desc");

        // then
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isOne();

        verify(mockTicketRepository).findAll(specCaptor.capture(), eq(DEFAULT_PAGE));
    }

    // ────────────────────────────────────────────────
    // Сценарии с пустыми доступными порталами
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("searchTickets → нет доступных порталов → возвращает пустую страницу")
    void searchTickets_noAccessiblePortals_returnsEmptyPage() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(List.of());
        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(List.of());

        PageResponse<TicketResponse> result = service.searchTickets("search", START_DATE, END_DATE, null, null, false, 0, 10, "createdAt",
                "desc");

        // then
        assertThat(result.content()).isEmpty();
        verifyNoInteractions(mockTicketRepository);
    }

    // Тест 1: owned пустые, но есть allowed → поиск продолжается
    @Test
    @DisplayName("searchTickets → owned порталы пустые, но есть allowed → поиск продолжается")
    void searchTickets_ownedEmptyButAllowedExists_searchContinues() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of()); // owned пустые

        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(List.of(createPortal(5L))); // allowed есть

        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(List.of());

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        PageResponse<TicketResponse> result = service.searchTickets(null, null, null, null, null, false, 0, 10, "createdAt", "desc");

        // then
        assertThat(result.content()).isEmpty();
        verify(mockTicketRepository).findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE));
    }

    // Тест 2: все доступные порталы пустые → сразу пустая страница, без вызова ticketRepository
    @Test
    @DisplayName("searchTickets → все доступные порталы пустые → сразу возвращает пустую страницу")
    void searchTickets_noAccessiblePortalsAtAll_returnsEmptyWithoutQuery() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(List.of());

        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(List.of());

        PageResponse<TicketResponse> result = service.searchTickets("любой поиск", START_DATE, END_DATE, TicketStatus.OPEN,
                TicketPriority.HIGH, false, 0, 10, "createdAt", "desc");

        // then
        assertThat(result.content()).isEmpty();
        verifyNoInteractions(mockTicketRepository);
    }

    // Тест 3: все фильтры null, но есть хотя бы один портал → поиск идёт
    @Test
    @DisplayName("searchTickets → все фильтры null, но есть порталы → ищет без ограничений")
    void searchTickets_allFiltersNullWithPortals_performsSearch() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(createPortal(1L)));

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        PageResponse<TicketResponse> result = service.searchTickets(null, null, null, null, null, false, 0, 10, "createdAt", "desc");

        // then
        assertThat(result.content()).isEmpty();
        verify(mockTicketRepository).findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE));
    }

    // Тест 4: isMyTickets = true → должен добавить фильтр по автору
    @Test
    @DisplayName("searchTickets → isMyTickets=true → фильтрует только по автору")
    void searchTickets_isMyTicketsTrue_addsAuthorFilter() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(createPortal(1L)));

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        service.searchTickets("bug", null, null, null, null, true, 0, 10, "createdAt", "desc");

        // then
        verify(mockTicketRepository).findAll(specCaptor.capture(), eq(DEFAULT_PAGE));
        assertThat(specCaptor.getValue()).isNotNull();
    }

    // ────────────────────────────────────────────────
    // Маппинг и параметры поиска
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("searchTickets → маппит модель заявки в TicketResponse")
    void searchTickets_foundTicket_mapsModelToResponse() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(createPortal(9L)));

        PortalModel portal = createPortal(9L);
        portal.setName("Портал Х");

        UserModel author = new UserModel();
        author.setId(55L);
        author.setLastName("Петров");
        author.setFirstName("Иван");

        Instant createdAt = Instant.parse("2025-01-15T10:00:00Z");

        TicketModel ticket = createTicket(100L);
        ticket.setTitle("Заявка на доступ");
        ticket.setBody("Тело заявки");
        ticket.setAuthor(author);
        ticket.setPortal(portal);
        ticket.setCreatedAt(createdAt);

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(new PageImpl<>(List.of(ticket), DEFAULT_PAGE, 1));

        PageResponse<TicketResponse> result = service.searchTickets("доступ", null, null, null, null, false, 0, 10, "createdAt", "desc");

        // then
        assertThat(result.content()).hasSize(1);

        TicketResponse response = result.content().get(0);
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.title()).isEqualTo("Заявка на доступ");
        assertThat(response.body()).isEqualTo("Тело заявки");
        assertThat(response.authorId()).isEqualTo(55L);
        assertThat(response.authorFullName()).isEqualTo("Петров Иван");
        assertThat(response.portalName()).isEqualTo("Портал Х");
        assertThat(response.portalId()).isEqualTo(9L);
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(response.ticketPriority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("searchTickets → заявка без автора и портала → null-поля в ответе")
    void searchTickets_ticketWithoutAuthorAndPortal_mapsNulls() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(createPortal(1L)));

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(new PageImpl<>(List.of(createTicket(101L)), DEFAULT_PAGE, 1));

        PageResponse<TicketResponse> result = service.searchTickets(null, null, null, null, null, false, 0, 10, "createdAt", "desc");

        TicketResponse response = result.content().get(0);
        // then
        assertThat(response.authorId()).isNull();
        assertThat(response.portalName()).isNull();
        assertThat(response.portalId()).isNull();
    }

    @Test
    @DisplayName("searchTickets → доступные порталы собираются из всех трёх источников текущего пользователя")
    void searchTickets_userWithPortals_queriesAllThreePortalSources() {

        // given
        // when
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(List.of(createPortal(5L)));
        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(List.of());
        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        service.searchTickets("поиск", null, null, null, null, false, 0, 10, "createdAt", "desc");

        // then
        verify(mockPortalRepository).findAllByOwnerIdOrderByCreatedAtDesc(USER_ID);
        verify(mockPortalRepository).findAllAccessibleByUserId(USER_ID);
        verify(mockPortalRepository).findPublicPortalIdsWithUserTickets(USER_ID);
        verify(mockTicketRepository).findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE));
    }

    @Test
    @DisplayName("searchTickets → пробрасывает page/size/sortBy/order и сохраняет итоги страницы")
    void searchTickets_secondPageRequested_passesParamsAndKeepsTotals() {

        // given
        // when
        PageRequest secondPage = PageRequest.of(1, 2, Sort.by("title").ascending());

        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(createPortal(1L)));

        when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(secondPage)))
                .thenReturn(new PageImpl<>(List.of(createTicket(201L), createTicket(202L)), secondPage, 5));

        PageResponse<TicketResponse> result = service.searchTickets(null, null, null, null, null, false, 1, 2, "title", "asc");

        // then
        assertThat(result.content()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.last()).isFalse();

        verify(mockTicketRepository).findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(secondPage));
    }

    @Test
    @DisplayName("searchTickets → недопустимое поле сортировки → ошибка пагинации, запрос в БД не уходит")
    void searchTickets_unknownSortField_isRejected() {

        // given / when / then
        assertThatThrownBy(() -> service.searchTickets(null, null, null, null, null, false, 0, 10, "passwordHash", "desc"))
                .isInstanceOf(PaginationException.class);

        verifyNoInteractions(mockTicketRepository);
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private PortalModel createPortal(Long id) {
        PortalModel p = new PortalModel();
        p.setId(id);
        p.setName("Portal " + id);
        p.setCreatedAt(Instant.now());
        p.setDeleted(false);
        p.setPublic(false);
        return p;
    }

    private TicketModel createTicket(Long id) {
        TicketModel t = new TicketModel();
        t.setId(id);
        t.setTitle("Test Ticket");
        t.setBody("Description");
        t.setTicketStatus(TicketStatus.OPEN);
        t.setTicketPriority(TicketPriority.MEDIUM);
        return t;
    }
}

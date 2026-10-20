package com.ukhanov.realhelpdesk.domain.ticket.repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("Спецификации поиска заявок (TicketSpecification)")
class TicketSpecificationTest {

    @Autowired
    private EntityManager em;

    private UserModel alice;
    private UserModel bob;

    private PortalModel portalA;
    private PortalModel portalB;

    private TicketModel t1; // Портал A, ошибка входа, OPEN/HIGH, 2025-02-10, alice
    private TicketModel t2; // Портал A, ошибка оплаты в теле, CLOSED/CRITICAL, 2025-05-20, bob
    private TicketModel t3; // Портал A, отчёты, IN_PROGRESS/LOW, 2024-12-31, alice
    private TicketModel t4; // Портал B, ошибка входа, OPEN/HIGH, 2025-03-01, bob
    private TicketModel t5; // Портал A, ошибка входа, live=DELETE, 2025-06-01, alice

    private static final Instant D1 = Instant.parse("2025-02-10T12:00:00Z");
    private static final Instant D2 = Instant.parse("2025-05-20T12:00:00Z");
    private static final Instant D3 = Instant.parse("2024-12-31T12:00:00Z");
    private static final Instant D4 = Instant.parse("2025-03-01T12:00:00Z");
    private static final Instant D5 = Instant.parse("2025-06-01T12:00:00Z");

    @BeforeEach
    void seed() {
        alice = user("alice@example.com", "Алиса", "Иванова");
        bob = user("bob@example.com", "Борис", "Сидоров");

        portalA = portal("Портал А", alice, false, Set.of());
        portalB = portal("Портал Б", bob, true, Set.of());
        portalA.setAllowedUserIds(Set.of(bob.getId()));

        t1 = ticket("Ошибка входа", "Не работает пароль", alice, portalA, TicketStatus.OPEN, TicketPriority.HIGH, TicketLiveStatus.ACTIVE,
                D1);
        t2 = ticket("Проблема с оплатой", "Карта не проходит, ошибка 402", bob, portalA, TicketStatus.CLOSED, TicketPriority.CRITICAL,
                TicketLiveStatus.ACTIVE, D2);
        t3 = ticket("Вопрос по отчётам", "Как выгрузить статистику", alice, portalA, TicketStatus.IN_PROGRESS, TicketPriority.LOW,
                TicketLiveStatus.ACTIVE, D3);
        t4 = ticket("Ошибка входа", "Не пускает в систему", bob, portalB, TicketStatus.OPEN, TicketPriority.HIGH, TicketLiveStatus.ACTIVE,
                D4);
        t5 = ticket("Ошибка входа (архив)", "Старая заявка", alice, portalA, TicketStatus.OPEN, TicketPriority.MEDIUM,
                TicketLiveStatus.DELETE, D5);
    }

    // ────────────────────────────────────────────────
    // search(): поиск по заголовку, телу и ФИО автора
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("search → ищет по заголовку без учёта регистра")
    void search_differentCaseQuery_returnsSameMatches() {

        // given
        // t1, t4, t5 — совпадение по заголовку; t2 — по телу ("ошибка 402")

        // when
        Set<Long> result = run(TicketSpecification.search("ОШИБКА"));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId(), t4.getId(), t5.getId());
    }

    @Test
    @DisplayName("search → ищет по телу заявки")
    void search_bodyText_returnsMatchingTickets() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.search("карта"));

        // then
        assertThat(result).containsExactlyInAnyOrder(t2.getId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("search → пустой или blank строка → спецификация не создаётся")
    void search_blankOrNull_returnsNull(String text) {

        // given
        // when
        Specification<TicketModel> specification = TicketSpecification.search(text);

        // then
        assertThat(specification).isNull();
    }

    @Test
    @DisplayName("search → нет совпадений → пустой результат")
    void search_noMatches_returnsEmpty() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.search("несуществующий текст"));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("search → совпадение только по телу, но не по заголовку → находит заявку")
    void search_textOnlyInBody_returnsThatTicket() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.search("статистику"));

        // then
        assertThat(result).containsExactly(t3.getId());
    }

    @Test
    @DisplayName("search → ищет по ФИО автора: имя и фамилия")
    void search_authorFirstNameOrLastName_returnsAuthorTickets() {

        // given
        // when
        Set<Long> byFirstName = run(TicketSpecification.search("алиса"));
        Set<Long> byLastName = run(TicketSpecification.search("СИДОРОВ"));

        // then
        assertThat(byFirstName).containsExactlyInAnyOrder(t1.getId(), t3.getId(), t5.getId());
        assertThat(byLastName).containsExactlyInAnyOrder(t2.getId(), t4.getId());
    }

    @Test
    @DisplayName("search → учитывает отчество автора")
    void search_authorMiddleName_returnsAuthorTickets() {

        // given
        // when
        bob.setMiddleName("Петрович");

        Set<Long> result = run(TicketSpecification.search("петрович"));

        // then
        assertThat(result).containsExactlyInAnyOrder(t2.getId(), t4.getId());
    }

    @Test
    @DisplayName("search → совпадение по автору не подмешивает заявки с чужим ФИО")
    void search_foreignAuthorQuery_doesNotLeakOtherAuthors() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.search("борис"));

        // then
        assertThat(result).containsExactlyInAnyOrder(t2.getId(), t4.getId());
        assertThat(result).doesNotContain(t1.getId(), t3.getId(), t5.getId());
    }

    // ────────────────────────────────────────────────
    // Элементарные фильтры
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("active → отсекает мягко удалённые заявки")
    void active_softDeletedExists_returnsOnlyActiveTickets() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.active());

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId(), t3.getId(), t4.getId());
    }

    @Test
    @DisplayName("inPortals → возвращает только заявки указанных порталов")
    void inPortals_singlePortalGiven_returnsOnlyItsTickets() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.inPortals(Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId(), t3.getId(), t5.getId());
    }

    @Test
    @DisplayName("onlyMy → возвращает только заявки автора")
    void onlyMy_userIdGiven_returnsOnlyAuthorTickets() {

        // given
        // when
        Set<Long> result = run(TicketSpecification.onlyMy(alice.getId()));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t3.getId(), t5.getId());
    }

    @Test
    @DisplayName("createdAfter/createdBefore → границы диапазона включительные")
    void createdAfterAndBefore_sameInstantBoundary_returnsTicket() {

        // given
        // when
        Set<Long> after = run(TicketSpecification.createdAfter(D1));
        Set<Long> before = run(TicketSpecification.createdBefore(D1));
        Set<Long> both = run(TicketSpecification.createdAfter(D1).and(TicketSpecification.createdBefore(D1)));

        // then
        assertThat(after).contains(t1.getId());
        assertThat(before).contains(t1.getId());
        assertThat(both).containsExactly(t1.getId());
    }

    @Test
    @DisplayName("withStatus/withPriority → фильтруют по статусу и приоритету")
    void withStatusAndPriority_closedCritical_returnsOnlyMatching() {

        // given
        // when
        Set<Long> byStatus = run(TicketSpecification.withStatus(TicketStatus.CLOSED));
        Set<Long> byPriority = run(TicketSpecification.withPriority(TicketPriority.CRITICAL));

        // then
        assertThat(byStatus).containsExactlyInAnyOrder(t2.getId());
        assertThat(byPriority).containsExactlyInAnyOrder(t2.getId());
    }

    // ────────────────────────────────────────────────
    // build(): сборка всех фильтров в одну спецификацию
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("build → без фильтров → все активные заявки доступных порталов")
    void build_withoutFilters_returnsAllActiveInPortals() {

        // given
        // when
        Set<Long> result = run(build(null, null, null, null, null, false, Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId(), t3.getId());
    }

    @Test
    @DisplayName("build → isMyTickets=false → не ограничивает по автору")
    void build_isMyTicketsFalse_doesNotFilterByAuthor() {

        // given
        // when
        Set<Long> result = run(build(null, null, null, null, null, false, Set.of(portalA.getId())));

        // then
        assertThat(result).contains(t2.getId());
    }

    @Test
    @DisplayName("build → isMyTickets=true → только заявки текущего пользователя")
    void build_isMyTicketsTrue_filtersByCurrentUser() {

        // given
        // when
        Set<Long> aliceResult = run(build(null, null, null, null, null, true, Set.of(portalA.getId())));
        Set<Long> bobResult = run(build(null, null, null, null, null, true, Set.of(portalA.getId()), bob.getId()));

        // then
        assertThat(aliceResult).containsExactlyInAnyOrder(t1.getId(), t3.getId());
        assertThat(bobResult).containsExactly(t2.getId());
    }

    @Test
    @DisplayName("build → статус и приоритет соединяются по И")
    void build_statusAndPriorityAnded_returnsEmptyResult() {

        // given — OPEN + CRITICAL одновременно ни у одной заявки нет

        // when
        Set<Long> result = run(build(null, null, null, TicketStatus.OPEN, TicketPriority.CRITICAL, false, Set.of(portalA.getId())));
        Set<Long> openOnly = run(build(null, null, null, TicketStatus.OPEN, null, false, Set.of(portalA.getId())));

        // then
        assertThat(result).isEmpty();
        assertThat(openOnly).containsExactly(t1.getId());
    }

    @Test
    @DisplayName("build → диапазон дат отсекает заявки вне периода")
    void build_oneYearRange_returnsTicketsInsidePeriod() {

        // given
        // when
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-12-31T23:59:59Z");

        Set<Long> result = run(build(null, start, end, null, null, false, Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId());
    }

    @Test
    @DisplayName("build → строка поиска из пробелов не применяется как фильтр")
    void build_blankSearch_isNotApplied() {

        // given
        // when
        Set<Long> result = run(build("   ", null, null, null, null, false, Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(t1.getId(), t2.getId(), t3.getId());
    }

    @Test
    @DisplayName("build → все фильтры вместе → точное совпадение")
    void build_allFiltersAtOnce_returnsExactMatch() {

        // given
        // when
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-12-31T23:59:59Z");

        Set<Long> result = run(build("ошибка", start, end, TicketStatus.OPEN, TicketPriority.HIGH, true, Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactly(t1.getId());
    }

    @Test
    @DisplayName("build → пустой или null набор порталов → IllegalArgumentException (сервис не вызывает build без порталов)")
    void build_withoutPortals_throws() {

        // given / when / then
        assertThatThrownBy(() -> build(null, null, null, null, null, false, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(null, null, null, null, null, false, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private Specification<TicketModel> build(String search, Instant start, Instant end, TicketStatus status, TicketPriority priority,
            Boolean isMy, Set<Long> portalIds) {
        return build(search, start, end, status, priority, isMy, portalIds, alice.getId());
    }

    private Specification<TicketModel> build(String search, Instant start, Instant end, TicketStatus status, TicketPriority priority,
            Boolean isMy, Set<Long> portalIds, Long userId) {
        return TicketSpecification.build(search, start, end, status, priority, isMy, userId, portalIds);
    }

    private Set<Long> run(Specification<TicketModel> specification) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TicketModel> query = cb.createQuery(TicketModel.class);
        Root<TicketModel> root = query.from(TicketModel.class);

        Predicate predicate = specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<TicketModel> result = em.createQuery(query).getResultList();
        return result.stream().map(TicketModel::getId).collect(Collectors.toSet());
    }

    private UserModel user(String email, String firstName, String lastName) {
        UserModel user = new UserModel();
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPasswordHash("hash");
        em.persist(user);
        return user;
    }

    private PortalModel portal(String name, UserModel owner, boolean isPublic, Set<Long> allowed) {
        PortalModel portal = new PortalModel();
        portal.setName(name);
        portal.setOwner(owner);
        portal.setPublic(isPublic);
        portal.setAllowedUserIds(allowed);
        em.persist(portal);
        return portal;
    }

    private TicketModel ticket(String title, String body, UserModel author, PortalModel portal, TicketStatus status,
            TicketPriority priority, TicketLiveStatus liveStatus, Instant createdAt) {
        TicketModel ticket = new TicketModel();
        ticket.setCreatedAt(createdAt);
        ticket.setTitle(title);
        ticket.setBody(body);
        ticket.setAuthor(author);
        ticket.setPortal(portal);
        ticket.setTicketStatus(status);
        ticket.setTicketPriority(priority);
        ticket.setTicketLiveStatus(liveStatus);
        em.persist(ticket);
        return ticket;
    }
}

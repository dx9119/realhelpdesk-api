package com.ukhanov.realhelpdesk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия N+1: количество запросов на типовых путях. Statistics считает JDBC prepared statements — если батч/EntityGraph сломается, цифра
 * вырастет и тест упадёт.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true"})
@DisplayName("Количество запросов на горячих путях (N+1 guard)")
class NPlus1GuardTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtRefreshTokenRepository jwtRefreshTokenRepository;

    @Autowired
    private PortalRepository portalRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("#13: загрузка пользователя по id не тянет refresh-токены (LAZY + @BatchSize)")
    void userFindById_withRefreshTokens_queriesOnce() {

        // given
        // when
        UserModel user = saveUser("user1@example.com");
        for (int i = 0; i < 3; i++) {
            RefreshTokenModel token = new RefreshTokenModel();
            token.setTokenRefresh(UUID.randomUUID().toString().replace("-", ""));
            token.setUser(user);
            jwtRefreshTokenRepository.save(token);
        }
        em.flush();
        em.clear();

        Statistics stats = statistics();
        stats.clear();

        UserModel loaded = userRepository.findById(user.getId()).orElseThrow();

        // then
        assertThat(loaded.getEmail()).isEqualTo("user1@example.com");
        assertThat((int) stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("#5: список порталов грузит составы участников батчем, а не по порталу")
    void portalList_withAllowedUsers_usesBatchLoading() {

        // given
        // when
        UserModel owner = saveUser("owner@example.com");
        UserModel member = saveUser("member@example.com");
        for (int i = 0; i < 20; i++) {
            PortalModel portal = new PortalModel();
            portal.setOwner(owner);
            portal.setName("Портал " + i);
            portal.setAllowedUserIds(new HashSet<>(Set.of(member.getId())));
            portalRepository.save(portal);
        }
        em.flush();
        em.clear();

        Statistics stats = statistics();
        stats.clear();

        List<PortalModel> portals = portalRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId());

        // then
        assertThat(portals).hasSize(20);
        assertThat(portals).allMatch(portal -> portal.getAllowedUserIds().size() == 1);

        // 1 запрос списка + 1 батч составов (20 порталов укладываются в @BatchSize)
        assertThat((int) stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("#2: страница тикетов по фильтру — автор и портал в ответе без N+1")
    void ticketSearch_withFilters_returnsPageWithoutNPlus1() {

        // given
        // when
        UserModel author = saveUser("author@example.com");
        PortalModel portal = savePortal(author, "Портал");
        for (int i = 0; i < 20; i++) {
            TicketModel ticket = new TicketModel();
            ticket.setTitle("Заявка " + i);
            ticket.setBody("Тело заявки");
            ticket.setAuthor(author);
            ticket.setPortal(portal);
            ticketRepository.save(ticket);
        }
        em.flush();
        em.clear();

        Statistics stats = statistics();
        stats.clear();

        // null-спецификация = «без фильтра», тот же findAll(spec, pageable), что вызывает контроллер поиска
        Specification<TicketModel> noFilter = null;

        Page<TicketModel> page = ticketRepository.findAll(noFilter, PageRequest.of(0, 25));

        // then
        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(
                ticket -> "author@example.com".equals(ticket.getAuthor().getEmail()) && portal.getId().equals(ticket.getPortal().getId()));

        // 1 запрос страницы с join-графом + 1 count-запрос; доступ к author/portal допросов не добавляет
        assertThat((int) stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private UserModel saveUser(String email) {
        UserModel user = new UserModel();
        user.setFirstName("Имя");
        user.setLastName("Фамилия");
        user.setEmail(email);
        user.setPasswordHash("hash");
        return userRepository.save(user);
    }

    private PortalModel savePortal(UserModel owner, String name) {
        PortalModel portal = new PortalModel();
        portal.setOwner(owner);
        portal.setName(name);
        portal.setAllowedUserIds(new HashSet<>());
        return portalRepository.save(portal);
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }
}

package com.ukhanov.realhelpdesk.core.security.accesscontrol;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.TicketAccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Доступ к заявкам: заявка всегда должна принадлежать порталу из запроса")
class TicketAccessValidationServiceTest {

    private static final Long PORTAL_ID = 1L;
    private static final Long FOREIGN_PORTAL_ID = 2L;
    private static final Long TICKET_ID = 42L;

    private UserModel owner;
    private UserModel attacker;
    private UserModel stranger;

    private PortalModel portal;
    private PortalModel foreignPortal;
    private TicketModel ticket;

    private UserModel currentUser;
    private TicketAccessValidationService service;

    @BeforeEach
    void setUp() {
        owner = user();
        attacker = user();
        stranger = user();

        portal = portal(PORTAL_ID, owner, Set.of(attacker.getId()));
        foreignPortal = portal(FOREIGN_PORTAL_ID, attacker, Set.of());

        ticket = new TicketModel();
        ticket.setId(TICKET_ID);
        ticket.setPortal(portal);
        ticket.setAuthor(owner);
        ticket.setAccessStatus(TicketAccessStatus.CREATOR_AND_PORTAL_USERS);

        currentUser = owner;

        PortalDomainService portalDomainService = new PortalDomainService(mock(PortalRepository.class)) {
            @Override
            public PortalModel getPortalById(Long portalId) throws PortalException {
                if (PORTAL_ID.equals(portalId)) {
                    return portal;
                }
                if (FOREIGN_PORTAL_ID.equals(portalId)) {
                    return foreignPortal;
                }
                throw new PortalException("Портал не найден");
            }
        };

        TicketDomainService ticketDomainService = new TicketDomainService(mock(TicketRepository.class)) {
            @Override
            public TicketModel findTicketById(Long ticketId) throws TicketException {
                if (TICKET_ID.equals(ticketId)) {
                    return ticket;
                }
                throw new TicketException("Заявка не найдена!");
            }
        };

        CurrentUserProvider currentUserProvider = new CurrentUserProvider(null) {
            @Override
            public UserModel getCurrentUserModel() {
                return currentUser;
            }
        };

        service = new TicketAccessValidationService(portalDomainService, currentUserProvider, ticketDomainService);
    }

    @Test
    @DisplayName("Владелец чужого портала не может изменять заявку другого портала")
    void hasTicketChange_ownerOfForeignPortal_denied() throws Exception {
        // given
        currentUser = attacker;

        // атакующий владеет foreignPortal и подставляет его ID в URL
        // when / then
        assertThat(service.hasTicketChange(FOREIGN_PORTAL_ID, TICKET_ID)).isFalse();
    }

    @Test
    @DisplayName("Посторонний пользователь не читает заявку чужого портала")
    void hasTicketAccess_stranger_denied() throws Exception {
        // given
        currentUser = stranger;
        // when
        portal.setAllowedUserIds(Set.of());

        // then
        assertThat(service.hasTicketAccess(PORTAL_ID, TICKET_ID)).isFalse();
    }

    @Test
    @DisplayName("Заявка с доступом ALL_USERS не читается через чужой portalId")
    void hasTicketAccess_allUsersButForeignPortal_denied() throws Exception {
        // given
        currentUser = stranger;
        // when
        ticket.setAccessStatus(TicketAccessStatus.ALL_USERS);

        // then
        assertThat(service.hasTicketAccess(FOREIGN_PORTAL_ID, TICKET_ID)).isFalse();
    }

    @Test
    @DisplayName("Владелец портала может изменять заявку своего портала")
    void hasTicketChange_ownerOfOwnPortal_allowed() throws Exception {
        // given
        currentUser = owner;

        // when / then
        assertThat(service.hasTicketChange(PORTAL_ID, TICKET_ID)).isTrue();
    }

    @Test
    @DisplayName("Пользователь с доступом к порталу читает его заявку")
    void hasTicketAccess_allowedUser_allowed() throws Exception {
        // given
        currentUser = attacker;

        // when / then
        assertThat(service.hasTicketAccess(PORTAL_ID, TICKET_ID)).isTrue();
    }

    @Test
    @DisplayName("Автор заявки может её изменять")
    void hasTicketChange_author_allowed() throws Exception {
        // given
        currentUser = stranger;
        // when
        ticket.setAuthor(stranger);

        // then
        assertThat(service.hasTicketChange(PORTAL_ID, TICKET_ID)).isTrue();
    }

    private long userSeq;

    private UserModel user() {
        UserModel userModel = new UserModel();
        userModel.setId(++userSeq);
        return userModel;
    }

    private PortalModel portal(Long id, UserModel ownerModel, Set<Long> allowedUserIds) {
        PortalModel portalModel = new PortalModel();
        portalModel.setId(id);
        portalModel.setOwner(ownerModel);
        portalModel.setAllowedUserIds(new HashSet<>(allowedUserIds));
        return portalModel;
    }
}

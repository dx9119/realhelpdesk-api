package com.ukhanov.realhelpdesk.core.security.accesscontrol.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class TicketAccessValidationService {
    private final PortalDomainService portalDomainService;
    private final CurrentUserProvider currentUserProvider;
    private final TicketDomainService ticketDomainService;

    public boolean hasTicketAccess(Long portalId, Long ticketId) throws PortalException, TicketException {
        Long currentUserId = currentUserProvider.getCurrentUserModel().getId();

        PortalModel portal = portalDomainService.getPortalById(portalId);
        TicketModel ticket = ticketDomainService.findTicketById(ticketId);

        if (!belongsToPortal(ticket, portalId)) {
            logger.warn("Доступ запрещён: заявка {} не относится к порталу {}", ticketId, portalId);
            return false;
        }

        TicketAccessStatus status = ticket.getAccessStatus();

        if (status == TicketAccessStatus.ALL_USERS) {
            return true;
        }

        if (status == TicketAccessStatus.CREATOR_AND_PORTAL_USERS) {
            Set<Long> allowedUsers = new HashSet<>(portal.getAllowedUserIds());
            allowedUsers.add(portal.getOwner().getId());
            allowedUsers.add(ticket.getAuthor().getId());
            return allowedUsers.contains(currentUserId);
        }

        logger.warn("Доступ запрещён: политика доступа заявки {} не допускает пользователя {}", ticketId,
                currentUserId);
        return false;
    }

    public boolean hasTicketChange(Long portalId, Long ticketId) throws PortalException, TicketException {
        UserModel user = currentUserProvider.getCurrentUserModel();

        PortalModel portal = portalDomainService.getPortalById(portalId);
        TicketModel ticket = ticketDomainService.findTicketById(ticketId);

        if (!belongsToPortal(ticket, portalId)) {
            logger.warn("Доступ запрещён: заявка {} не относится к порталу {}", ticketId, portalId);
            return false;
        }

        Set<Long> allowedUsers = new HashSet<>(portal.getAllowedUserIds());
        allowedUsers.add(portal.getOwner().getId());
        allowedUsers.add(ticket.getAuthor().getId());
        Long currentUserId = user.getId();

        // Изменить статус заявки может либо автор заявки, либо владельцы портала.
        boolean allowed = allowedUsers.contains(currentUserId);
        if (!allowed) {
            logger.warn("Доступ запрещён: пользователь {} не может изменять заявку {}", currentUserId, ticketId);
        }
        return allowed;
    }

    // Заявка должна принадлежать тому порталу, от которого пришёл запрос,
    // иначе владелец случайного портала получил бы доступ к чужой заявке
    private boolean belongsToPortal(TicketModel ticket, Long portalId) {
        PortalModel ticketPortal = ticket.getPortal();
        return ticketPortal != null && portalId.equals(ticketPortal.getId());
    }

}

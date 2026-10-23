package com.ukhanov.realhelpdesk.core.security.accesscontrol.service;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class AccessValidationService {
    private final PortalDomainService portalDomainService;
    private final CurrentUserProvider currentUserProvider;

    public boolean hasPortalAccess(Long portalId) throws PortalException {
        PortalModel portal = portalDomainService.getPortalById(portalId);
        Long currentUserId = currentUserProvider.getCurrentUserModel().getId();

        boolean isOwner = portal.getOwner().getId().equals(currentUserId);
        boolean isAllowedUser = portal.getAllowedUserIds().contains(currentUserId);
        boolean isPublic = portal.isPublic();

        if (!isOwner && !isAllowedUser && !isPublic) {
            logger.warn("Доступ запрещён: пользователь {} не имеет доступа к порталу {}", currentUserId, portalId);
            return false;
        }

        return true;
    }
    public boolean hasPortalOwner(Long portalId) throws PortalException {
        PortalModel portal = portalDomainService.getPortalById(portalId);
        Long currentUserId = currentUserProvider.getCurrentUserModel().getId();

        boolean isOwner = portal.getOwner().getId().equals(currentUserId);

        if (!isOwner) {
            logger.warn("Доступ запрещён: пользователь {} не является владельцем портала {}", currentUserId, portalId);
            return false;
        }

        return true;
    }

    // Управление порталом (переименование, список участников): владелец или
    // доверенные пользователи. Публичный доступ (isPublic) права управления не даёт.
    public boolean hasPortalManageAccess(Long portalId) throws PortalException {
        PortalModel portal = portalDomainService.getPortalById(portalId);
        Long currentUserId = currentUserProvider.getCurrentUserModel().getId();

        boolean isOwner = portal.getOwner().getId().equals(currentUserId);
        boolean isAllowedUser = portal.getAllowedUserIds() != null
                && portal.getAllowedUserIds().contains(currentUserId);

        if (!isOwner && !isAllowedUser) {
            logger.warn("Доступ запрещён: пользователь {} не имеет прав управления порталом {}", currentUserId,
                    portalId);
            return false;
        }

        return true;
    }

}

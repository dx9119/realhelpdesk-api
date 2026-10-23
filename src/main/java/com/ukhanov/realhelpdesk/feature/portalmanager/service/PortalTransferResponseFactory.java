package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferRequestModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Сборка ответа о передаче портала и безопасное разрешение участников (удалённый пользователь не ломает ответ). */
@Slf4j
// @Component, а не @Service: сборка DTO — не бизнес-операция (правило именования @Service)
@Component
@RequiredArgsConstructor
public class PortalTransferResponseFactory {

    private final PortalDomainService portalDomainService;
    private final UserDomainService userDomainService;

    public PortalTransferResponse toResponse(PortalTransferRequestModel transfer, PortalModel portal,
            UserModel initiator, UserModel proposed) {
        UserModel proposedUser = proposed != null ? proposed : safeUser(transfer.getProposedOwnerId());
        return new PortalTransferResponse(transfer.getId(), portal.getId(), portal.getName(),
                initiator != null ? displayName(initiator) : null,
                proposedUser != null ? proposedUser.getEmail() : null,
                transfer.getReasonInitiator(), transfer.getReasonProposed(), transfer.isKeepOldOwner(),
                transfer.getStatus().name(),
                transfer.getCreatedAt(), transfer.getExpiresAt(), transfer.getDecidedAt());
    }

    public UserModel safeUser(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return userDomainService.getUserById(userId);
        } catch (UsernameNotFoundException e) {
            logger.debug("Участник передачи {} удалён, имя не подставлено", userId);
            return null;
        }
    }

    public PortalModel safePortal(Long portalId) {
        try {
            return portalDomainService.getPortalById(portalId);
        } catch (PortalException e) {
            // Портала нет — для ответа о передаче это ожидаемо. Всё остальное (например, сбой БД)
            // должно падать громко, а не превращаться в portal=null.
            logger.warn("Портал {} не найден при сборке ответа о передаче: {}", portalId, e.getMessage());
            return null;
        }
    }

    private static String displayName(UserModel user) {
        if (user == null) {
            return "Пользователь не существует";
        }
        String name = (user.getLastName() + " " + user.getFirstName()).trim();
        return name.isBlank() ? user.getEmail() : name;
    }
}

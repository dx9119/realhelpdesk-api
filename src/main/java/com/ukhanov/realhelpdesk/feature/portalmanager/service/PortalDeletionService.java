package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ukhanov.realhelpdesk.core.exception.SafeSideEffectExecutor;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.DeleteResult;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Удаление порталов: безопасное мягкое удаление с отправкой уведомлений. Каждый портал удаляется в собственной
 * транзакции, чтобы сбой одного не откатывал остальные, а email/оповещения запускаются после коммита через
 * {@link SafeSideEffectExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalDeletionService {

    private final CurrentUserProvider currentUserProvider;
    private final PortalDomainService portalDomainService;
    private final AccessValidationService accessValidationService;
    private final PortalHistoryService portalHistoryService;
    private final EmailDeliveryService emailDeliveryService;
    private final EmailTemplates emailTemplates;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Массовое удаление порталов: последовательно, по одной транзакции на портал. Оповещения и письма — после коммита
     * всех удалений.
     */
    public DeleteResult deletePortals(Set<Long> portalIdSet) throws PortalException {
        Objects.requireNonNull(portalIdSet, "portalIdSet не должен быть null");
        UserModel user = currentUserProvider.getCurrentUserModel();

        Set<Long> deletedIds = new LinkedHashSet<>();
        List<PortalModel> deletedPortals = new ArrayList<>();
        for (Long id : portalIdSet) {
            PortalModel portal = deletePortalInOwnTransaction(id, user);
            if (portal != null) {
                deletedIds.add(id);
                deletedPortals.add(portal);
            }
        }

        for (PortalModel portal : deletedPortals) {
            SafeSideEffectExecutor.runSafely(() -> {
                notificationPublisher.publishToPortalUsers(portal, NotificationEvent.PORTAL_DELETED, user.getId(), null,
                        portal.getName());
                emailDeliveryService.initNotifyPortalUsers(portal, emailTemplates.deletedPortalSubject(portal.getId()),
                        emailTemplates.deletedPortalBody(portal.getId(), user.getEmail()),
                        NotificationEvent.PORTAL_DELETED);
                return null;
            }, e -> logger.warn("Не удалось уведомить о удалении портала {}: {}", portal.getId(), e.getMessage()));
        }

        logger.info("Массовое удаление порталов: запрошено={}, удалено={}, userId={}", portalIdSet.size(),
                deletedIds.size(), user.getId());
        return new DeleteResult(deletedIds.size(), Set.copyOf(deletedIds));
    }

    /** Мягкое удаление одного портала в собственной транзакции; null — нет прав владельца или ошибка. */
    private PortalModel deletePortalInOwnTransaction(Long portalId, UserModel user) {
        return SafeSideEffectExecutor.callSafely(() -> transactionTemplate.execute(status -> {
            try {
                if (!accessValidationService.hasPortalOwner(portalId)) {
                    return null;
                }
                PortalModel portal = portalDomainService.getPortalById(portalId);
                portal.setTimeDelete(Instant.now());
                portal.setDeleted(true);
                portalDomainService.savePortal(portal);

                portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.PORTAL_DELETED,
                        user.getId(), null, null, "name", portal.getName(), null));
                return portal;
            } catch (PortalException e) {
                throw new IllegalStateException(e);
            }
        }), e -> {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Не удалось удалить портал с ID {}: {}", portalId, cause.getMessage());
            return null;
        });
    }
}

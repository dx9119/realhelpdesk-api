package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;

import jakarta.mail.MessagingException;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.exception.SafeSideEffectExecutor;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferRequestModel;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.PublishNotificationCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Оповещения о передаче владения порталом: in-app и email. Вынесены из {@code PortalOwnerTransferService}, потому что
 * это best-effort side effects, а не бизнес-инварианты передачи.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalTransferNotificationService {

    private final NotificationPublisher notificationPublisher;
    private final EmailDeliveryService emailDeliveryService;
    private final EmailTemplates emailTemplates;
    private final UserDomainService userDomainService;

    public void notifyTransferRequested(PortalModel portal, PortalTransferRequestModel transfer, UserModel initiator,
            UserModel proposed) {
        String initiatorName = displayName(initiator);
        String participantsTitle = "Владелец портала «" + portal.getName() + "» (" + initiatorName
                + ") начал передачу портала";
        String proposedTitle = "Вам передают портал «" + portal.getName() + "» (портал #" + portal.getId() + ")";

        Set<Long> participants = portalUserIds(portal);
        participants.remove(proposed.getId());

        notificationPublisher.publishToUsers(
                new PublishNotificationCommand(participants, NotificationEvent.PORTAL_TRANSFER_REQUESTED,
                        initiator.getId(), null,
                        portal.getId(), participantsTitle));
        notificationPublisher.publishToUsers(
                new PublishNotificationCommand(Set.of(proposed.getId()), NotificationEvent.PORTAL_TRANSFER_REQUESTED,
                        initiator.getId(), null,
                        portal.getId(), proposedTitle));

        quietly("инициирование передачи портала " + portal.getId(), () -> {
            if (!participants.isEmpty()) {
                emailDeliveryService.sendPortalUsersNotification(participants,
                        emailTemplates.portalTransferRequestedSubject(portal.getId()),
                        emailTemplates.portalTransferRequestedBody(portal.getId(), initiatorName,
                                transfer.getReasonInitiator()),
                        NotificationEvent.PORTAL_TRANSFER_REQUESTED);
            }
            emailDeliveryService.sendUserNotification(proposed.getEmail(),
                    emailTemplates.portalTransferProposedSubject(portal.getId()),
                    emailTemplates.portalTransferProposedBody(portal.getId(), initiatorName,
                            transfer.getReasonInitiator()),
                    NotificationEvent.PORTAL_TRANSFER_REQUESTED, proposed.getId());
        });
    }

    public void notifyTransferAccepted(PortalModel portal, PortalTransferRequestModel transfer, UserModel oldOwner,
            UserModel newOwner, Set<Long> newAllowedUsers) {
        String newOwnerName = displayName(newOwner);
        String title = "Портал «" + portal.getName() + "» теперь принадлежит " + newOwnerName;

        Set<Long> recipients = new HashSet<>(newAllowedUsers);
        recipients.add(oldOwner.getId());

        notificationPublisher.publishToUsers(
                new PublishNotificationCommand(recipients, NotificationEvent.PORTAL_TRANSFER_ACCEPTED, newOwner.getId(),
                        null, portal.getId(),
                        title));
        recipients.remove(newOwner.getId());

        quietly("подтверждение передачи портала " + portal.getId(),
                () -> emailDeliveryService.sendPortalUsersNotification(recipients,
                        emailTemplates.portalTransferAcceptedSubject(portal.getId()),
                        emailTemplates.portalTransferAcceptedBody(portal.getId(), newOwnerName,
                                transfer.getReasonInitiator()),
                        NotificationEvent.PORTAL_TRANSFER_ACCEPTED));
    }

    public void notifyTransferRejected(PortalModel portal, PortalTransferRequestModel transfer, UserModel initiator,
            UserModel proposed) {
        String proposedName = displayName(proposed);
        String title = "Запрос на передачу портала «" + portal.getName() + "» отклонён";

        notificationPublisher.publishToUsers(new PublishNotificationCommand(
                Set.of(initiator != null ? initiator.getId() : transfer.getInitiatorId()),
                NotificationEvent.PORTAL_TRANSFER_REJECTED, proposed.getId(), null, portal.getId(), title));

        if (initiator != null) {
            quietly("отклонение передачи портала " + portal.getId(),
                    () -> emailDeliveryService.sendUserNotification(initiator.getEmail(),
                            emailTemplates.portalTransferRejectedSubject(portal.getId()),
                            emailTemplates.portalTransferRejectedBody(portal.getId(), proposedName,
                                    transfer.getReasonProposed()),
                            NotificationEvent.PORTAL_TRANSFER_REJECTED, initiator.getId()));
        }
    }

    public void notifyTransferCancelled(PortalModel portal, PortalTransferRequestModel transfer, UserModel initiator) {
        String initiatorName = displayName(initiator);
        String title = "Передача портала «" + portal.getName() + "» отозвана владельцем";
        UserModel proposed = safeUser(transfer.getProposedOwnerId());

        notificationPublisher.publishToUsers(new PublishNotificationCommand(Set.of(transfer.getProposedOwnerId()),
                NotificationEvent.PORTAL_TRANSFER_CANCELLED,
                initiator.getId(), null, portal.getId(), title));

        if (proposed != null) {
            quietly("отзыв передачи портала " + portal.getId(),
                    () -> emailDeliveryService.sendUserNotification(proposed.getEmail(),
                            emailTemplates.portalTransferCancelledSubject(portal.getId()),
                            emailTemplates.portalTransferCancelledBody(portal.getId(), initiatorName),
                            NotificationEvent.PORTAL_TRANSFER_CANCELLED, proposed.getId()));
        }
    }

    public void notifyTransferExpired(PortalModel portal, PortalTransferRequestModel transfer, UserModel initiator) {
        String title = "Истёк срок передачи портала «" + portal.getName() + "»";

        notificationPublisher.publishToUsers(new PublishNotificationCommand(Set.of(initiator.getId()),
                NotificationEvent.PORTAL_TRANSFER_EXPIRED, null,
                null,
                portal.getId(), title));

        quietly("истечение срока передачи портала " + portal.getId(),
                () -> emailDeliveryService.sendUserNotification(initiator.getEmail(),
                        emailTemplates.portalTransferExpiredSubject(portal.getId()),
                        emailTemplates.portalTransferExpiredBody(portal.getId()),
                        NotificationEvent.PORTAL_TRANSFER_EXPIRED, initiator.getId()));
    }

    private Set<Long> portalUserIds(PortalModel portal) {
        Set<Long> users = new HashSet<>();
        if (portal.getOwner() != null) {
            users.add(portal.getOwner().getId());
        }
        if (portal.getAllowedUserIds() != null) {
            users.addAll(portal.getAllowedUserIds());
        }
        return users;
    }

    private UserModel safeUser(Long userId) {
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

    private static String displayName(UserModel user) {
        if (user == null) {
            return "Пользователь не существует";
        }
        String name = (user.getLastName() + " " + user.getFirstName()).trim();
        return name.isBlank() ? user.getEmail() : name;
    }

    private void quietly(String what, EmailAction action) {
        SafeSideEffectExecutor.runSafely(() -> {
            action.run();
            return null;
        }, e -> logger.warn("Не удалось отправить email ({}) : {}", what, e.getMessage()));
    }

    @FunctionalInterface
    private interface EmailAction {
        void run() throws MessagingException, UnsupportedEncodingException;
    }
}

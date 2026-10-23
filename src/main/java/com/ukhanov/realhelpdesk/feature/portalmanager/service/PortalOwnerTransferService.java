package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.transaction.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service.LoginService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferRequestModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferStatus;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalTransferRequestRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferConfirmRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRejectRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Передача владения порталом: владелец инициирует запрос (своим паролем и причиной), предлагаемый владелец подтверждает
 * или отклоняет запрос своим паролем, владелец может отозвать запрос до решения. Запрос действует ограниченный срок и
 * закрывается лениво как EXPIRED при следующем обращении.
 *
 * <p>
 * Каждое решение пишется в историю портала одной транзакцией с изменением запроса; оповещения (in-app и email)
 * критичной операцией не считаются — их сбой логируется и не откатывает передачу.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class PortalOwnerTransferService {

    /** Срок действия запроса на передачу: после него запрос лениво закрывается как EXPIRED. */
    private static final Duration TRANSFER_REQUEST_TTL = Duration.ofHours(72);

    private final CurrentUserProvider currentUserProvider;
    private final PortalDomainService portalDomainService;
    private final PortalTransferRequestRepository transferRequestRepository;
    private final PortalHistoryService portalHistoryService;
    private final UserDomainService userDomainService;
    private final LoginService loginService;
    private final LimitService limitService;
    private final PortalTransferNotificationService notificationService;
    private final PortalTransferResponseFactory responseFactory;
    /**
     * Инициирование передачи владельцем: пароль владельца подтверждает личность, активный запрос может быть только
     * один.
     */
    @Transactional
    public PortalTransferResponse initiate(Long portalId, PortalTransferRequest request) throws PortalException {
        Objects.requireNonNull(request, "Запрос на передачу не должен быть null");

        PortalModel portal = portalDomainService.getPortalById(portalId);
        UserModel initiator = currentUserProvider.getCurrentUserModel();
        requireOwner(portal, initiator);

        if (!loginService.isPasswordValid(request.password(), initiator.getPasswordHash())) {
            throw new PortalException("Неверный пароль", HttpStatus.UNAUTHORIZED);
        }

        UserModel proposed = findActiveUserByEmail(request.email());

        if (proposed.getId().equals(initiator.getId())) {
            throw new PortalException("Нельзя передать портал самому себе");
        }

        Optional<PortalTransferRequestModel> existing = findPending(portalId);
        if (existing.isPresent()) {
            if (!expireIfStale(existing.get())) {
                throw new PortalException("Активный запрос на передачу портала уже существует");
            }
        }

        PortalTransferRequestModel transfer = new PortalTransferRequestModel();
        transfer.setPortalId(portalId);
        transfer.setInitiatorId(initiator.getId());
        transfer.setProposedOwnerId(proposed.getId());
        transfer.setReasonInitiator(request.reason());
        transfer.setKeepOldOwner(Boolean.TRUE.equals(request.keepOldOwnerAsMember()));
        transfer.setStatus(PortalTransferStatus.PENDING);
        transfer.setExpiresAt(Instant.now().plus(TRANSFER_REQUEST_TTL));
        transferRequestRepository.save(transfer);

        portalHistoryService.record(
                new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.TRANSFER_REQUESTED, initiator.getId(),
                        proposed.getId(), request.reason(),
                        null, null, null));

        logger.info("Инициирована передача портала {} с пользователя {} на пользователя {}", portalId,
                initiator.getId(), proposed.getId());
        notificationService.notifyTransferRequested(portal, transfer, initiator, proposed);

        return responseFactory.toResponse(transfer, portal, initiator, proposed);
    }

    /** Подтверждение передачи предлагаемым владельцем: его пароль и причина; портал переходит новому владельцу. */
    @Transactional
    public PortalTransferResponse confirm(Long portalId, PortalTransferConfirmRequest request)
            throws PortalException, LimitException {
        Objects.requireNonNull(request, "Запрос на подтверждение не должен быть null");

        PortalModel portal = portalDomainService.getPortalById(portalId);
        PortalTransferRequestModel transfer = requirePendingForCurrentUser(portalId);

        if (!loginService.isPasswordValid(request.password(),
                currentUserProvider.getCurrentUserModel().getPasswordHash())) {
            throw new PortalException("Неверный пароль", HttpStatus.UNAUTHORIZED);
        }

        UserModel proposed = currentUserProvider.getCurrentUserModel();
        if (proposed.getUserStatus() != UserStatus.ACTIVE) {
            throw new PortalException("Учётная запись нового владельца не активна — передача невозможна");
        }

        UserModel oldOwner = portal.getOwner();

        Set<Long> allowed = portal.getAllowedUserIds() == null
                ? new HashSet<>()
                : new HashSet<>(portal.getAllowedUserIds());
        allowed.remove(proposed.getId());
        boolean keepOldOwner = transfer.isKeepOldOwner();
        if (keepOldOwner) {
            allowed.add(oldOwner.getId());
        }
        if (limitService.violatesSharedUserPortalLimit(proposed, portal, allowed.size())) {
            throw new LimitException("Достигнут лимит на количество пользователей с доступом к порталу");
        }

        portal.setOwner(proposed);
        portal.setAllowedUserIds(allowed);
        portalDomainService.savePortal(portal);

        transfer.setStatus(PortalTransferStatus.ACCEPTED);
        transfer.setReasonProposed(request.reason());
        transfer.setDecidedAt(Instant.now());
        transferRequestRepository.save(transfer);

        portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.TRANSFER_ACCEPTED,
                proposed.getId(), oldOwner.getId(),
                request.reason(),
                null, null, null));

        logger.info("Портал {} передан: владелец {} → {}", portalId, oldOwner.getId(), proposed.getId());
        notificationService.notifyTransferAccepted(portal, transfer, oldOwner, proposed, allowed);

        return responseFactory.toResponse(transfer, portal, oldOwner, proposed);
    }

    /** Отклонение передачи предлагаемым владельцем: причина уходит инициатору и в историю. */
    @Transactional
    public PortalTransferResponse reject(Long portalId, PortalTransferRejectRequest request) throws PortalException {
        Objects.requireNonNull(request, "Запрос на отклонение не должен быть null");

        PortalModel portal = portalDomainService.getPortalById(portalId);
        PortalTransferRequestModel transfer = requirePendingForCurrentUser(portalId);
        UserModel proposed = currentUserProvider.getCurrentUserModel();
        UserModel initiator = responseFactory.safeUser(transfer.getInitiatorId());

        transfer.setStatus(PortalTransferStatus.REJECTED);
        transfer.setReasonProposed(request.reason());
        transfer.setDecidedAt(Instant.now());
        transferRequestRepository.save(transfer);

        portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.TRANSFER_REJECTED,
                proposed.getId(), initiator.getId(),
                request.reason(),
                null, null, null));

        logger.info("Передача портала {} отклонена пользователем {}", portalId, proposed.getId());
        notificationService.notifyTransferRejected(portal, transfer, initiator, proposed);

        return responseFactory.toResponse(transfer, portal, initiator, proposed);
    }

    /** Отзыв запроса владельцем до решения предлагаемого владельца. */
    @Transactional
    public PortalTransferResponse cancel(Long portalId) throws PortalException {
        PortalModel portal = portalDomainService.getPortalById(portalId);
        UserModel initiator = currentUserProvider.getCurrentUserModel();
        requireOwner(portal, initiator);

        PortalTransferRequestModel transfer = findPending(portalId).orElseThrow(() -> notFoundRequest());

        transfer.setStatus(PortalTransferStatus.CANCELLED);
        transfer.setDecidedAt(Instant.now());
        transferRequestRepository.save(transfer);

        portalHistoryService.record(
                new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.TRANSFER_CANCELLED, initiator.getId(),
                        transfer.getProposedOwnerId(), null,
                        null, null, null));

        logger.info("Передача портала {} отозвана владельцем {}", portalId, initiator.getId());
        notificationService.notifyTransferCancelled(portal, transfer, initiator);

        return responseFactory.toResponse(transfer, portal, initiator, null);
    }

    /**
     * Активный запрос: виден инициатору (владельцу) и предлагаемому владельцу — у последнего ещё нет доступа к порталу,
     * поэтому авторизация проверяется здесь, а не через @PreAuthorize. Истёкший запрос закрывается лениво и дальше не
     * отдаётся.
     */
    public PortalTransferResponse getPending(Long portalId) throws PortalException {
        PortalModel portal = portalDomainService.getPortalById(portalId);
        PortalTransferRequestModel transfer = findPending(portalId)
                .orElseThrow(PortalOwnerTransferService::notFoundRequest);

        Long currentUserId = currentUserProvider.getCurrentUserId();
        boolean visible = currentUserId.equals(transfer.getInitiatorId())
                || currentUserId.equals(transfer.getProposedOwnerId());
        if (!visible) {
            // чужим пользователям не раскрываем существование запроса
            throw notFoundRequest();
        }

        if (expireIfStale(transfer)) {
            throw notFoundRequest();
        }

        return responseFactory.toResponse(transfer, portal, responseFactory.safeUser(transfer.getInitiatorId()),
                responseFactory.safeUser(transfer.getProposedOwnerId()));
    }

    /**
     * Истёкший активный запрос закрывается как EXPIRED с историей и оповещением инициатора; возвращает true, если
     * закрыли.
     */
    private boolean expireIfStale(PortalTransferRequestModel transfer) {
        if (transfer.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }

        transfer.setStatus(PortalTransferStatus.EXPIRED);
        transfer.setDecidedAt(Instant.now());
        transferRequestRepository.save(transfer);

        portalHistoryService.record(
                new PortalHistoryRecordCommand(transfer.getPortalId(), PortalHistoryEvent.TRANSFER_EXPIRED, null,
                        transfer.getProposedOwnerId(), null,
                        null, null, null));

        logger.info("Истёк срок передачи портала {}, запрос {} закрыт", transfer.getPortalId(), transfer.getId());

        PortalModel portal = responseFactory.safePortal(transfer.getPortalId());
        UserModel initiator = responseFactory.safeUser(transfer.getInitiatorId());
        if (portal != null && initiator != null) {
            notificationService.notifyTransferExpired(portal, transfer, initiator);
        }
        return true;
    }

    private PortalTransferRequestModel requirePendingForCurrentUser(Long portalId) throws PortalException {
        PortalTransferRequestModel transfer = findPending(portalId)
                .orElseThrow(PortalOwnerTransferService::notFoundRequest);

        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!currentUserId.equals(transfer.getProposedOwnerId())) {
            // только предлагаемый владелец видит и решает запрос; остальным — как будто запроса нет
            throw notFoundRequest();
        }

        if (expireIfStale(transfer)) {
            throw new PortalException("Срок действия запроса на передачу портала истёк");
        }
        return transfer;
    }

    private Optional<PortalTransferRequestModel> findPending(Long portalId) {
        return transferRequestRepository.findByPortalIdAndStatus(portalId, PortalTransferStatus.PENDING);
    }

    private void requireOwner(PortalModel portal, UserModel user) throws PortalException {
        if (user == null || portal.getOwner() == null || !user.getId().equals(portal.getOwner().getId())) {
            throw new PortalException("Передачу портала может выполнять только владелец", HttpStatus.FORBIDDEN);
        }
    }

    private UserModel findActiveUserByEmail(String email) throws PortalException {
        UserModel user;
        try {
            user = userDomainService.getUserByEmail(email);
        } catch (UsernameNotFoundException e) {
            throw new PortalException("Пользователь с таким email не найден", HttpStatus.NOT_FOUND, e);
        }
        if (user.getUserStatus() != UserStatus.ACTIVE) {
            throw new PortalException("Учётная запись нового владельца не активна — передача невозможна");
        }
        return user;
    }

    private static PortalException notFoundRequest() {
        return new PortalException("Активный запрос на передачу портала не найден", HttpStatus.NOT_FOUND);
    }

}

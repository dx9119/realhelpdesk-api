package com.ukhanov.realhelpdesk.domain.portal.service;

import java.util.List;
import java.util.Objects;

import jakarta.transaction.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PortalDomainService {

    private final PortalRepository portalRepository;

    public PortalDomainService(PortalRepository portalRepository) {
        this.portalRepository = Objects.requireNonNull(portalRepository, "portalRepository не должен быть null");
    }

    public List<PortalModel> getPortalsByOwnerId(Long ownerId) {
        Objects.requireNonNull(ownerId, "ownerId не должен быть null");
        logger.debug("Получение всех порталов владельца с ID: {}", ownerId);
        return portalRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public Page<PortalModel> getPortalsPageByOwnerId(Long ownerId, Pageable pageable) {
        Objects.requireNonNull(ownerId, "ownerId не должен быть null");
        logger.debug("Получение порталов владельца с ID: {} с пагинацией", ownerId);
        return portalRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
    }

    /**
     * Портал по id: состав участников читается сразу (JOIN), а не лениво — настройка open-in-view выключена, поэтому
     * после закрытия транзакции LAZY-коллекция на detached-сущности не догрузится.
     */
    public PortalModel getPortalById(Long portalId) throws PortalException {
        Objects.requireNonNull(portalId, "portalId must not be null");
        logger.debug("Получен портал ID: {}", portalId);
        return portalRepository.findWithAccessById(portalId)
                .orElseThrow(() -> PortalException.notFound("Портал с ID " + portalId + " не найден"));
    }

    @Transactional
    public PortalModel savePortal(PortalModel portal) {
        Objects.requireNonNull(portal, "portal не должен быть null");
        logger.debug("Сохранение портала {}", portal.getId());
        return portalRepository.save(portal);
    }

    public boolean isPortalExistByName(Long ownerId, String portalName) {
        Objects.requireNonNull(portalName, "portalName не должен быть null");
        return portalRepository.existsByOwnerIdAndNameAndIsDeletedFalse(ownerId, portalName);
    }

    public Page<PortalModel> getPortalPageAccessByUser(Long userId, Pageable pageable) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        logger.debug("Получение доступных порталов для пользователя с ID: {} с пагинацией", userId);
        return portalRepository.findAccessibleByUserId(userId, pageable);
    }

    @Transactional
    public boolean deletePortalById(Long portalId) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.info("Удаление портала по ID: {}", portalId);
        portalRepository.deleteById(portalId);
        return true;
    }

    public List<PortalModel> getAllSharedPortals(Long userId) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        logger.debug("Получение всех доступных порталов для пользователя с ID: {}", userId);
        return portalRepository.findAllAccessibleByUserId(userId);
    }

    public List<Long> getPublicPortalsByUserActivity(Long userId) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        logger.debug("Получение публичных порталов по активности пользователя с ID: {}", userId);
        return portalRepository.findPublicPortalIdsWithUserTickets(userId);
    }
}

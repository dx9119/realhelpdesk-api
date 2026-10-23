package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.mail.MessagingException;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PatchPortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalInfoResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.UpdatePortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.portalmanager.mapper.PortalMapper;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Жизненный цикл портала: создание, список, реквизиты и публичность.
 *
 * <p>
 * Состав участников — {@link PortalAccessService}, массовое удаление — {@link PortalDeletionService}, чтение истории —
 * {@link PortalHistoryQueryService}: у этих операций свои инварианты и свои зависимости.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class PortalManageService {

    private static final Set<String> SORTABLE_PORTAL_FIELDS = Set.of("createdAt", "name");

    private final CurrentUserProvider currentUserProvider;
    private final PortalDomainService portalDomainService;
    private final PaginationAdapter paginationAdapter;
    private final LimitService limitService;
    private final EmailDeliveryService emailDeliveryService;
    private final EmailTemplates emailTemplates;
    private final NotificationPublisher notificationPublisher;
    private final PortalHistoryService portalHistoryService;
    private final PortalMapper portalMapper;

    public CreatePortalResponse createPortal(CreatePortalRequest request)
            throws PortalException, LimitException, UserManageException, MessagingException,
            UnsupportedEncodingException {
        Objects.requireNonNull(request, "Запрос на создание портала не должен быть null");

        UserModel userModel = currentUserProvider.getCurrentUserModel();
        PortalModel portal = portalMapper.toEntity(request, userModel);

        if (!userModel.isEmailVerified()) {
            throw new UserManageException("Подтвердите ваш адрес электронной почты, далее вы сможете создать портал");
        }

        if (limitService.hasUserReachedPortalLimit(userModel)) {
            throw new LimitException("Пользователь достиг лимита на количество порталов");
        }

        if (portalDomainService.isPortalExistByName(portal.getOwner().getId(), portal.getName())) {
            throw new PortalException("Портал с именем '" + portal.getName() + "' уже существует");
        }

        // Тут портал получил ID
        PortalModel savePortal = portalDomainService.savePortal(portal);

        logger.info("Портал создан для пользователя {} с именем '{}'", userModel.getId(), portal.getName());

        // In-app оповещение о новом портале: на момент создания участников ещё нет, получателем остаётся только
        // создатель —
        // publisher исключает автора, поэтому строк не появится (точка входа нужна для паритета с email)
        notificationPublisher.publishToPortalUsers(portal, NotificationEvent.NEW_PORTAL, userModel.getId(), null,
                portal.getName());

        // Отправляем письмо
        emailDeliveryService.initNotifyPortalUsers(portal, emailTemplates.portalCreatedSubject(savePortal.getId()),
                emailTemplates.portalCreatedBody(savePortal.getId()), NotificationEvent.NEW_PORTAL);

        return new CreatePortalResponse(savePortal.getId());
    }

    public List<PortalResponse> getAllPortals() {

        UserModel userModel = currentUserProvider.getCurrentUserModel();
        return portalDomainService.getPortalsByOwnerId(userModel.getId())
                .stream()
                .map(portalMapper::toResponse)
                .toList();
    }

    public List<Long> getAllPortalIds() {

        UserModel userModel = currentUserProvider.getCurrentUserModel();
        return portalDomainService.getPortalsByOwnerId(userModel.getId()).stream().map(PortalModel::getId).toList();
    }

    public PageResponse<PortalResponse> getPagePortalsByOwner(int page, int size, String sortBy, String order) {
        UserModel userModel = currentUserProvider.getCurrentUserModel();
        PageRequest pageRequest = paginationAdapter.buildPageRequest(page, size, sortBy, order, SORTABLE_PORTAL_FIELDS);

        Page<PortalModel> portalPage = portalDomainService.getPortalsPageByOwnerId(userModel.getId(), pageRequest);
        Page<PortalResponse> mappedPage = portalPage.map(portalMapper::toResponse);

        return paginationAdapter.mapToResponse(mappedPage, sortBy, order);
    }

    public PageResponse<PortalResponse> getPagePortalsByAccess(int page, int size, String sortBy, String order) {
        UserModel userModel = currentUserProvider.getCurrentUserModel();
        PageRequest pageRequest = paginationAdapter.buildPageRequest(page, size, sortBy, order, SORTABLE_PORTAL_FIELDS);

        Page<PortalModel> portalPage = portalDomainService.getPortalPageAccessByUser(userModel.getId(), pageRequest);
        Page<PortalResponse> mappedPage = portalPage.map(portalMapper::toResponse);

        return paginationAdapter.mapToResponse(mappedPage, sortBy, order);
    }

    @Transactional
    public void setPortalStatus(Long portalId, boolean isPublic) throws PortalException {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        logger.info("Публичность портала {} изменена на {}", portalId, isPublic);

        PortalModel portal;
        try {
            portal = portalDomainService.getPortalById(portalId);
        } catch (IllegalArgumentException e) {
            throw new PortalException("Портал с ID " + portalId + " не найден", HttpStatus.NOT_FOUND, e);
        }
        boolean oldPublic = portal.isPublic();
        portal.setPublic(isPublic);
        portalDomainService.savePortal(portal);

        if (oldPublic != isPublic) {
            portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.VISIBILITY_CHANGED,
                    currentUserProvider.getCurrentUserId(), null, null,
                    "visibility", String.valueOf(oldPublic), String.valueOf(isPublic)));
        }
    }

    public Boolean getStatusPortal(Long portalId) throws PortalException {
        PortalModel portalModel = portalDomainService.getPortalById(portalId);
        return portalModel.isPublic();
    }

    private List<PortalModel> getAccessiblePortals(Long userId) {
        return Stream
                .concat(portalDomainService.getPortalsByOwnerId(userId).stream(),
                        portalDomainService.getAllSharedPortals(userId).stream())
                .toList();
    }

    public List<Long> getUserActivityInPublicPortals() {
        Long userId = currentUserProvider.getCurrentUserId();
        return portalDomainService.getPublicPortalsByUserActivity(userId);
    }

    public List<Long> mapAccessiblePortalsToIds() {
        Long userId = currentUserProvider.getCurrentUserModel().getId();
        return new ArrayList<>(getAccessiblePortals(userId).stream().map(PortalModel::getId).toList());
    }

    public List<PortalInfoResponse> mapAccessiblePortalsToInfo() {
        Long userId = currentUserProvider.getCurrentUserModel().getId();
        return getAccessiblePortals(userId).stream().map(p -> new PortalInfoResponse(p.getId(), p.getName())).toList();
    }

    public PortalInfoResponse getPortalInfo(Long portalId) throws PortalException {
        Objects.requireNonNull(portalId, "portalId не должен быть null");

        PortalModel portal = portalDomainService.getPortalById(portalId);
        return toInfoResponse(portal);
    }

    @Transactional
    public PortalInfoResponse updatePortalInfo(Long portalId, UpdatePortalInfoRequest request, String ifMatch)
            throws PortalException {
        Objects.requireNonNull(request, "UpdatePortalInfoRequest не должен быть null");

        try {
            PortalModel portal = portalDomainService.getPortalById(portalId);
            requireFreshVersion(portal, ifMatch);

            String oldName = portal.getName();
            String oldDescription = portal.getDescription();

            portal.setName(request.name());
            portal.setDescription(request.description());

            portalDomainService.savePortal(portal);
            logger.info("Портал {} обновлён: имя — {}, описание — {}", portal.getId(), portal.getName(),
                    portal.getDescription());

            Long actorId = currentUserProvider.getCurrentUserId();
            if (!Objects.equals(oldName, request.name())) {
                portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.NAME_CHANGED,
                        actorId, null, null, "name",
                        oldName,
                        request.name()));
            }
            if (!Objects.equals(oldDescription, request.description())) {
                portalHistoryService.record(new PortalHistoryRecordCommand(portalId,
                        PortalHistoryEvent.DESCRIPTION_CHANGED, actorId, null, null,
                        "description",
                        oldDescription, request.description()));
            }

            return toInfoResponse(portal);
        } catch (OptimisticLockException e) {
            throw new PortalException("Данные портала были недавно изменены другим пользователем. "
                    + "Обновите страницу - получите актуальные данные и попробуйте снова.");
        }
    }

    /**
     * Частичное обновление: меняются только переданные поля. Полная замена — {@code PUT}: там клиент обязан прислать
     * ресурс целиком, а здесь достаточно одного поля.
     */
    @Transactional
    public PortalInfoResponse patchPortalInfo(Long portalId, PatchPortalInfoRequest request, String ifMatch)
            throws PortalException {
        Objects.requireNonNull(request, "PatchPortalInfoRequest не должен быть null");
        if (request.isEmpty()) {
            throw new PortalException("Не передано ни одного поля для изменения", HttpStatus.BAD_REQUEST);
        }

        try {
            PortalModel portal = portalDomainService.getPortalById(portalId);
            requireFreshVersion(portal, ifMatch);

            Long actorId = currentUserProvider.getCurrentUserId();
            if (request.name() != null && !Objects.equals(portal.getName(), request.name())) {
                String oldName = portal.getName();
                portal.setName(request.name());
                portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.NAME_CHANGED,
                        actorId, null, null, "name",
                        oldName,
                        request.name()));
            }
            if (request.description() != null && !Objects.equals(portal.getDescription(), request.description())) {
                String oldDescription = portal.getDescription();
                portal.setDescription(request.description());
                portalHistoryService.record(new PortalHistoryRecordCommand(portalId,
                        PortalHistoryEvent.DESCRIPTION_CHANGED, actorId, null, null,
                        "description",
                        oldDescription, request.description()));
            }

            portalDomainService.savePortal(portal);
            logger.info("Портал {} обновлён частично (PATCH)", portalId);
            return toInfoResponse(portal);
        } catch (OptimisticLockException e) {
            throw new PortalException("Данные портала были недавно изменены другим пользователем. "
                    + "Обновите страницу - получите актуальные данные и попробуйте снова.");
        }
    }

    private PortalInfoResponse toInfoResponse(PortalModel portal) {
        return new PortalInfoResponse(portal.getId(), portal.getName(), portal.getDescription(), portal.getVersion());
    }

    /** If-Match: клиент присылает версию, которую видел; несовпадение — 412 до записи, а не 409 после. */
    private void requireFreshVersion(PortalModel portal, String ifMatch) throws PortalException {
        if (!EntityTag.satisfiedBy(ifMatch, EntityTag.of("portal", portal.getVersion()), portal.getVersion())) {
            throw new PortalException("Портал уже изменён: данные устарели, обновите страницу и повторите",
                    HttpStatus.PRECONDITION_FAILED);
        }
    }
}

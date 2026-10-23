package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserDetailsProjection;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalSettingsResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.UserInfo;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Состав участников портала: замена списка допущенных пользователей и выдача настроек портала.
 *
 * <p>
 * Вынесено из {@code PortalManageService}: это отдельная зона ответственности — границы доступа портала, а не его
 * жизненный цикл. Запись пишется в ту же историю портала, что и остальные изменения.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAccessService {

    private final PortalDomainService portalDomainService;
    private final CurrentUserProvider currentUserProvider;
    private final PortalUtilsService portalUtilsService;
    private final LimitService limitService;
    private final UserDomainService userDomainService;
    private final PortalHistoryService portalHistoryService;

    /** Полная замена состава участников: лимит проверяется до записи, старый состав уходит в историю. */
    @Transactional
    public void replacePortalUsers(Long portalId, Set<Long> newAccessUserId) throws PortalException, LimitException {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        Objects.requireNonNull(newAccessUserId, "newAccessUserId не должен быть null");

        portalUtilsService.validateIdList(newAccessUserId);

        PortalModel portal = portalDomainService.getPortalById(portalId);
        UserModel user = currentUserProvider.getCurrentUserModel();

        if (limitService.violatesSharedUserPortalLimit(user, portal, newAccessUserId.size())) {
            throw new LimitException("Достигнут лимит на количество пользователей с доступом к порталу");
        }

        Set<Long> oldAccessUserId = portal.getAllowedUserIds() == null
                ? Set.of()
                : Set.copyOf(portal.getAllowedUserIds());
        portal.setAllowedUserIds(new HashSet<>(newAccessUserId));
        portalDomainService.savePortal(portal);
        logger.info("Пользователи {} успешно добавлены к порталу {}", newAccessUserId, portalId);

        portalHistoryService.record(new PortalHistoryRecordCommand(portalId, PortalHistoryEvent.USERS_CHANGED,
                user.getId(), null, null, "users",
                joinUserIds(oldAccessUserId), joinUserIds(newAccessUserId)));
    }

    /** Настройки портала: участники и флаг публичности. */
    public PortalSettingsResponse getPortalSettings(Long portalId) throws PortalException {
        Objects.requireNonNull(portalId, "portalId не должен быть null");

        PortalModel portal = portalDomainService.getPortalById(portalId);

        // Один батч-запрос по всем участникам вместо find по каждому (N+1)
        Set<Long> portalUserIds = portal.getAllowedUserIds();
        Map<Long, UserDetailsProjection> usersById = userDomainService.getUserDetailsByIds(portalUserIds);

        List<UserInfo> userInfoList = new ArrayList<>();
        for (Long userId : portalUserIds) {
            UserDetailsProjection user = usersById.get(userId);
            if (user != null) {
                userInfoList.add(new UserInfo(user.getId(), user.getFirstName(), user.getLastName(),
                        user.getMiddleName(), user.getEmail()));
            } else {
                logger.debug("Участник портала {} не найден в БД, подставлена заглушка", userId);
                userInfoList.add(new UserInfo(userId, "Пользователь не существует", "-", "-", "none@none.none"));
            }
        }

        return new PortalSettingsResponse(userInfoList, portal.isPublic());
    }

    private String joinUserIds(Set<Long> userIds) {
        return userIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}

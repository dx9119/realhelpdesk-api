package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserDetailsProjection;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalHistoryResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Чтение истории портала: страницы событий с подстановкой отображаемых имён участников.
 *
 * <p>
 * Вынесено из {@code PortalManageService}: сборка ответа истории — отдельная зона чтения, не связанная с изменением
 * портала.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalHistoryQueryService {

    private static final Set<String> PORTAL_HISTORY_SORTABLE_FIELDS = Set.of("createdAt", "id");

    private final PortalHistoryService portalHistoryService;
    private final UserDomainService userDomainService;
    private final PaginationAdapter paginationAdapter;

    /** История портала (передачи владения и изменения портала). Доступ: участники портала (см. контроллер). */
    public PageResponse<PortalHistoryResponse> getPortalHistory(Long portalId, int page, int size, String sortBy,
            String order) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");

        PageRequest pageRequest = paginationAdapter.buildPageRequest(page, size, sortBy, order,
                PORTAL_HISTORY_SORTABLE_FIELDS);
        Page<PortalHistoryModel> historyPage = portalHistoryService.getPage(portalId, pageRequest);

        // Имена авторов и целей загружаются одним батчем на страницу, а не по запросу на запись истории
        Map<Long, String> displayNames = loadDisplayNames(historyPage);
        Page<PortalHistoryResponse> mappedPage = historyPage.map(entry -> toResponse(entry, displayNames));

        return paginationAdapter.mapToResponse(mappedPage);
    }

    private PortalHistoryResponse toResponse(PortalHistoryModel entry, Map<Long, String> displayNames) {
        return new PortalHistoryResponse(entry.getId(), entry.getPortalId(), entry.getEvent().name(),
                entry.getActorId(),
                entry.getActorId() == null ? null : displayNames.get(entry.getActorId()), entry.getTargetUserId(),
                entry.getTargetUserId() == null ? null : displayNames.get(entry.getTargetUserId()), entry.getReason(),
                entry.getFieldName(),
                entry.getOldValue(), entry.getNewValue(), entry.getCreatedAt());
    }

    /** Отображаемые имена участников записей страницы истории: удалённый пользователь не должен ломать выдачу. */
    private Map<Long, String> loadDisplayNames(Page<PortalHistoryModel> historyPage) {
        Set<Long> userIds = historyPage.getContent()
                .stream()
                .flatMap(entry -> Stream.of(entry.getActorId(), entry.getTargetUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, UserDetailsProjection> usersById = userDomainService.getUserDetailsByIds(userIds);
        Map<Long, String> displayNames = new HashMap<>();
        for (Long userId : userIds) {
            UserDetailsProjection user = usersById.get(userId);
            if (user == null) {
                logger.debug("Автор записи истории {} не найден, имя заменено", userId);
                displayNames.put(userId, "Пользователь не существует");
            } else {
                String name = (user.getLastName() + " " + user.getFirstName()).trim();
                displayNames.put(userId, name.isBlank() ? user.getEmail() : name);
            }
        }
        return displayNames;
    }
}

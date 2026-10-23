package com.ukhanov.realhelpdesk.feature.ticketmanager.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageCriteria;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketSearchCriteria;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketSpecification;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.mapper.TicketMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class TicketSearchService {

    /** Поля, по которым разрешена сортировка: список закрытый, иначе чужое поле даёт 500 вместо 400. */
    private static final Set<String> SORTABLE_TICKET_FIELDS = Set.of("createdAt", "title", "ticketStatus",
            "ticketPriority", "id");

    private final TicketRepository ticketRepository;
    private final PortalRepository portalRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TicketMapper ticketMapper;
    private final PaginationAdapter paginationAdapter;

    public PageResponse<TicketResponse> searchTickets(TicketSearchCriteria criteria, PageCriteria pageCriteria) {

        Long userId = currentUserProvider.getCurrentUserId();

        logger.debug(
                "Запрос на поиск тикетов. Пользователь: {}, Поиск: '{}', Статус: {}, Приоритет: {}, "
                        + "Только мои: {}, Дата от: {}, Дата до: {}",
                userId, criteria.search(), criteria.status(), criteria.priority(), criteria.mine(),
                criteria.startDate(),
                criteria.endDate());

        PageRequest pageRequest = paginationAdapter.buildPageRequest(pageCriteria.page(), pageCriteria.size(),
                pageCriteria.sortBy(), pageCriteria.order(), SORTABLE_TICKET_FIELDS);
        Set<Long> accessiblePortalIds = getAccessiblePortalIds(userId);

        if (accessiblePortalIds.isEmpty()) {
            logger.debug("У пользователя {} нет доступа ни к одному порталу", userId);
            return paginationAdapter.mapToResponse(Page.empty(pageRequest));
        }

        Specification<TicketModel> spec = TicketSpecification.build(criteria, userId, accessiblePortalIds);

        Page<TicketModel> found = ticketRepository.findAll(spec, pageRequest);
        return paginationAdapter.mapToResponse(found.map(ticketMapper::toResponse));
    }

    private Set<Long> getAccessiblePortalIds(Long userId) {
        List<PortalModel> ownedPortals = portalRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId);
        List<PortalModel> allowedPortals = portalRepository.findAllAccessibleByUserId(userId);
        List<Long> publicPortalIds = portalRepository.findPublicPortalIdsWithUserTickets(userId);

        Set<Long> portalIds = new HashSet<>();
        ownedPortals.stream().map(PortalModel::getId).forEach(portalIds::add);
        allowedPortals.stream().map(PortalModel::getId).forEach(portalIds::add);
        portalIds.addAll(publicPortalIds);

        return portalIds;
    }
}

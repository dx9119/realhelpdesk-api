package com.ukhanov.realhelpdesk.domain.ticket.repository;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketSearchCriteria;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

public final class TicketSpecification {

    private TicketSpecification() {
    }

    // Фильтр: только активные тикеты
    public static Specification<TicketModel> active() {
        return (root, query, cb) -> cb.equal(root.get("ticketLiveStatus"), TicketLiveStatus.ACTIVE);
    }

    // Фильтр: тикеты только из доступных порталов
    public static Specification<TicketModel> inPortals(Set<Long> portalIds) {
        return (root, query, cb) -> root.get("portal").get("id").in(portalIds);
    }

    // Фильтр: тикеты, созданные текущим пользователем
    public static Specification<TicketModel> onlyMy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("author").get("id"), userId);
    }

    // Фильтр: поиск по заголовку, телу тикета и ФИО автора
    public static Specification<TicketModel> search(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        return (root, query, cb) -> {
            String pattern = "%" + text.toLowerCase() + "%";
            Join<TicketModel, UserModel> author = root.join("author", JoinType.LEFT);
            return cb.or(cb.like(cb.lower(root.get("title")), pattern), cb.like(cb.lower(root.get("body")), pattern),
                    cb.like(cb.lower(author.get("firstName")), pattern),
                    cb.like(cb.lower(author.get("lastName")), pattern),
                    cb.like(cb.lower(author.get("middleName")), pattern));
        };
    }

    // Фильтр: создан после указанной даты
    public static Specification<TicketModel> createdAfter(Instant start) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start);
    }

    // Фильтр: создан до указанной даты
    public static Specification<TicketModel> createdBefore(Instant end) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), end);
    }

    // Фильтр: по статусу тикета
    public static Specification<TicketModel> withStatus(TicketStatus status) {
        return (root, query, cb) -> cb.equal(root.get("ticketStatus"), status);
    }

    // Фильтр: по приоритету тикета
    public static Specification<TicketModel> withPriority(TicketPriority priority) {
        return (root, query, cb) -> cb.equal(root.get("ticketPriority"), priority);
    }

    // Основной билдер спецификаций: собирает все фильтры в одну спецификацию
    public static Specification<TicketModel> build(TicketSearchCriteria criteria, Long currentUserId,
            Set<Long> accessiblePortalIds) {

        // Если нет доступных порталов — возвращаем пустой результат
        if (accessiblePortalIds == null || accessiblePortalIds.isEmpty()) {
            return Specification.where((Specification<TicketModel>) null);
        }

        Specification<TicketModel> spec = Specification.where(active()).and(inPortals(accessiblePortalIds));

        if (criteria.mine()) {
            spec = spec.and(onlyMy(currentUserId));
        }

        if (StringUtils.hasText(criteria.search())) {
            spec = spec.and(search(criteria.search()));
        }

        if (criteria.startDate() != null) {
            spec = spec.and(createdAfter(criteria.startDate()));
        }

        if (criteria.endDate() != null) {
            spec = spec.and(createdBefore(criteria.endDate()));
        }

        if (criteria.status() != null) {
            spec = spec.and(withStatus(criteria.status()));
        }

        if (criteria.priority() != null) {
            spec = spec.and(withPriority(criteria.priority()));
        }

        return spec;
    }

}

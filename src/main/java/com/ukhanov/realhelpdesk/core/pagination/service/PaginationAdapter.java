package com.ukhanov.realhelpdesk.core.pagination.service;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.exception.PaginationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PaginationAdapter {

    public <T> PageResponse<T> mapToResponse(Page<T> page, String sortBy, String order) {
        return mapToResponse(page);
    }

    public <T> PageResponse<T> mapToResponse(Page<T> page) {
        Objects.requireNonNull(page, "Защита от null: Page необходим для маппинга");

        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }

    public PageRequest buildPageRequest(int page, int size, String sortBy, String order,
            Set<String> allowedSortFields) {
        Objects.requireNonNull(sortBy, "Параметр sortBy не должен быть null");
        Objects.requireNonNull(order, "Параметр order не должен быть null");
        Objects.requireNonNull(allowedSortFields, "allowedSortFields не должен быть null");

        logger.debug("Пагинация: page={}, size={}, sort={}", page, size, sortBy);

        if (!allowedSortFields.contains(sortBy)) {
            throw new PaginationException("Недопустимое поле сортировки \"" + sortBy + "\". Допустимые поля: "
                    + String.join(", ", new TreeSet<>(allowedSortFields)));
        }

        Sort sort = resolveSortDirection(sortBy, order);
        return PageRequest.of(page, size, sort);
    }

    private Sort resolveSortDirection(String sortBy, String order) {
        return "desc".equalsIgnoreCase(order) ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
    }
}

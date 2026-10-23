package com.ukhanov.realhelpdesk.core.pagination.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Параметры пагинации и сортировки для {@code @ModelAttribute}-биндинга.
 *
 * <p>
 * Record, а не набор {@code @RequestParam}: один объект можно передать в сервис и переиспользовать в разных эндпоинтах.
 * Компактный конструктор подставляет значения по умолчанию только для отсутствующих параметров; явно переданные
 * невалидные значения (например, {@code size=0}) не маскируются и отклоняются Bean Validation.
 * </p>
 */
public record PageCriteria(
        @NotNull @Min(0) Integer page,
        @NotNull @Min(1) @Max(100) Integer size,
        String sortBy,
        String order) {

    private static final int DEFAULT_SIZE = 10;

    public PageCriteria {
        page = page == null ? 0 : page;
        size = size == null ? DEFAULT_SIZE : size;
        sortBy = sortBy == null ? "createdAt" : sortBy;
        order = order == null ? "desc" : order;
    }
}

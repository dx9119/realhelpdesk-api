package com.ukhanov.realhelpdesk.core.pagination.service;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.ukhanov.realhelpdesk.core.pagination.exception.PaginationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaginationAdapterTest {

    private static final Set<String> ALLOWED_FIELDS = Set.of("createdAt", "title", "ticketStatus");

    private final PaginationAdapter adapter = new PaginationAdapter();

    @Test
    @DisplayName("Разрешённое поле сортировки применяется к PageRequest")
    void buildPageRequest_allowedField_appliesSorting() {
        // given / when
        PageRequest request = adapter.buildPageRequest(1, 20, "title", "desc", ALLOWED_FIELDS);

        // then
        assertThat(request.getPageNumber()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(20);
        assertThat(request.getSort().getOrderFor("title")).isNotNull();
        assertThat(request.getSort().getOrderFor("title").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("Направление asc — сортировка по возрастанию")
    void buildPageRequest_directionAsc_sortsAscending() {
        // given / when
        PageRequest request = adapter.buildPageRequest(0, 10, "createdAt", "asc", ALLOWED_FIELDS);

        // then
        assertThat(request.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("Неизвестное поле сортировки → PaginationException с перечнем разрешённых полей")
    void buildPageRequest_unknownField_throwsPaginationException() {
        // given / when / then
        assertThatThrownBy(() -> adapter.buildPageRequest(0, 10, "constructor", "asc", ALLOWED_FIELDS))
                .isInstanceOf(PaginationException.class)
                .hasMessageContaining("\"constructor\"")
                .hasMessageContaining("createdAt")
                .hasMessageContaining("ticketStatus");
    }

    @Test
    @DisplayName("Вложенный путь в поле сортировки → PaginationException")
    void buildPageRequest_nestedPath_throwsPaginationException() {
        // given / when / then
        assertThatThrownBy(() -> adapter.buildPageRequest(0, 10, "author.createdAt", "desc", ALLOWED_FIELDS))
                .isInstanceOf(PaginationException.class);
    }

    @Test
    @DisplayName("SQL-вставка в поле сортировки → PaginationException")
    void buildPageRequest_sqlInjectionInSortField_throwsPaginationException() {
        // given / when / then
        assertThatThrownBy(() -> adapter.buildPageRequest(0, 10, "title; DROP TABLE tickets", "asc", ALLOWED_FIELDS))
                .isInstanceOf(PaginationException.class);
    }
}

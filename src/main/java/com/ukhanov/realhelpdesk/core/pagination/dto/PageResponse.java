package com.ukhanov.realhelpdesk.core.pagination.dto;

import java.util.List;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {

    public static class Builder<T> {
        private List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean last;

        public Builder<T> content(List<T> items) {
            this.content = items;
            return this;
        }

        public Builder<T> page(int pageNumber) {
            this.page = pageNumber;
            return this;
        }

        public Builder<T> size(int pageSize) {
            this.size = pageSize;
            return this;
        }

        public Builder<T> totalElements(long total) {
            this.totalElements = total;
            return this;
        }

        public Builder<T> totalPages(int pages) {
            this.totalPages = pages;
            return this;
        }

        public Builder<T> last(boolean isLast) {
            this.last = isLast;
            return this;
        }

        public PageResponse<T> build() {
            return new PageResponse<>(content, page, size, totalElements, totalPages, last);
        }
    }
}

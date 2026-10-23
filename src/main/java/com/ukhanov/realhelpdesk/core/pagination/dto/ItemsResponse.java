package com.ukhanov.realhelpdesk.core.pagination.dto;

import java.util.Collection;
import java.util.List;

public record ItemsResponse<T>(List<T> items, long total) {

    public static <T> ItemsResponse<T> of(Collection<T> items) {
        List<T> copy = List.copyOf(items);
        return new ItemsResponse<>(copy, copy.size());
    }
}

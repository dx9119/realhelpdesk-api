package com.ukhanov.realhelpdesk.core.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.filter.ServerHttpObservationFilter;

/** Помечает перехваченное @ControllerAdvice исключение в контексте наблюдения запроса. */
public final class ProblemObservation {

    private ProblemObservation() {
    }

    public static void mark(Throwable ex, HttpServletRequest request) {
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> context.setError(ex));
    }
}

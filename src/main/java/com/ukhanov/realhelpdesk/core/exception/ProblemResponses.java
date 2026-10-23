package com.ukhanov.realhelpdesk.core.exception;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("timestamp", Instant.now().toString());
        if (request != null && request.getRequestURI() != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return problem;
    }

    public static ProblemDetail withErrors(HttpStatus status, String detail, HttpServletRequest request,
            Map<String, String> errors) {
        ProblemDetail problem = of(status, detail, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    public static ResponseEntity<ProblemDetail> entity(HttpStatus status, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(of(status, detail, request));
    }

    public static ResponseEntity<ProblemDetail> entity(HttpStatus status, String detail, HttpServletRequest request,
            Map<String, String> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(withErrors(status, detail, request, errors));
    }
}

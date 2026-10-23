package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto;

public record TokenCleanupResponse(long revokedDeleted, int batches) {
}

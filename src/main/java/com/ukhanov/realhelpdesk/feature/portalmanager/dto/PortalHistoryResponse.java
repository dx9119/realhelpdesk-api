package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import java.time.Instant;

public record PortalHistoryResponse(Long id, Long portalId, String event, Long actorId, String actorName,
        Long targetUserId,
        String targetName, String reason, String fieldName, String oldValue, String newValue, Instant createdAt) {
}

package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import java.time.Instant;

public record PortalTransferResponse(Long id, Long portalId, String portalName, String initiatorName,
        String proposedOwnerEmail,
        String reasonInitiator, String reasonProposed, boolean keepOldOwnerAsMember, String status, Instant createdAt,
        Instant expiresAt,
        Instant decidedAt) {
}

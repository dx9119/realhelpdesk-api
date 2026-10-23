package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import java.time.Instant;

public record PortalResponse(Long id, String name, String description, Instant createdAt) {
}

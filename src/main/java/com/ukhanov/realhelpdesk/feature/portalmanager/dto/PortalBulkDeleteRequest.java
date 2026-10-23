package com.ukhanov.realhelpdesk.feature.portalmanager.dto;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Массовое удаление порталов. */
@Schema(description = "Массовое удаление порталов")
public record PortalBulkDeleteRequest(@NotEmpty Set<Long> ids) {
}

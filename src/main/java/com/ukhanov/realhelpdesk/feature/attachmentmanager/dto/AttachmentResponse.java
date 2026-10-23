package com.ukhanov.realhelpdesk.feature.attachmentmanager.dto;

import java.time.Instant;

public record AttachmentResponse(Long id, Long messageId, Long ticketId, String fileName, String contentType,
        Long sizeBytes,
        Long uploadedById, String uploadedByFullName, Instant createdAt, String downloadUrl) {
}

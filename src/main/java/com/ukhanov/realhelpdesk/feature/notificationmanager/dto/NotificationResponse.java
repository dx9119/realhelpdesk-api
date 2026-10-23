package com.ukhanov.realhelpdesk.feature.notificationmanager.dto;

import java.time.Instant;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

public record NotificationResponse(Long id, NotificationEvent event, Long ticketId, Long portalId, String title,
        boolean read,
        Instant createdAt) {
}

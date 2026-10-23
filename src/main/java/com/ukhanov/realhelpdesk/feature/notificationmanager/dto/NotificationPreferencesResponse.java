package com.ukhanov.realhelpdesk.feature.notificationmanager.dto;

import java.util.Set;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

public record NotificationPreferencesResponse(Set<NotificationEvent> events, boolean repeatEnabled,
        int repeatIntervalMinutes) {
}

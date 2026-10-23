package com.ukhanov.realhelpdesk.feature.notificationmanager.dto;

import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;

public record NotificationPreferencesRequest(
        @NotNull(message = "Список событий обязателен")
         @NotEmpty(message = "Список событий не может быть пустым") Set<NotificationEvent> events,
        Boolean repeatEnabled,
        @Min(value = MIN_REPEAT_MINUTES, message = "Интервал повтора от 1 минуты")
         @Max(value = MAX_REPEAT_MINUTES,
                 message = "Интервал повтора не больше недели") Integer repeatIntervalMinutes) {

    private static final int MIN_REPEAT_MINUTES = UserNotificationPreferencesModel.MIN_REPEAT_INTERVAL_MINUTES;
    private static final int MAX_REPEAT_MINUTES = UserNotificationPreferencesModel.MAX_REPEAT_INTERVAL_MINUTES;
}

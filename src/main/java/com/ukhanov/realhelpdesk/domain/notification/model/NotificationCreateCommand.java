package com.ukhanov.realhelpdesk.domain.notification.model;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

/**
 * Параметры создания in-app оповещения. {@code groupId} задаётся только при insert (колонка updatable=false) и
 * используется для строк-напоминаний повторов.
 */
public record NotificationCreateCommand(
        Long recipientId,
        NotificationEvent event,
        Long ticketId,
        Long portalId,
        String title,
        Long groupId) {

    public NotificationCreateCommand(Long recipientId, NotificationEvent event, Long ticketId, Long portalId,
            String title) {
        this(recipientId, event, ticketId, portalId, title, null);
    }
}

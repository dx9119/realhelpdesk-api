package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.util.Collection;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

/**
 * Команда публикации in-app оповещения: получатели, событие и контекст заявки/портала. Заменяет 6 позиционных
 * аргументов, в которых легко перепутать {@code actorId}/{@code ticketId}/{@code portalId}.
 */
public record PublishNotificationCommand(
        Collection<Long> recipients,
        NotificationEvent event,
        Long actorId,
        Long ticketId,
        Long portalId,
        String title) {
}

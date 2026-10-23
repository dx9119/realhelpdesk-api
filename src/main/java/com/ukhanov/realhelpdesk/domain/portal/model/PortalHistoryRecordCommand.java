package com.ukhanov.realhelpdesk.domain.portal.model;

/**
 * Команда записи события истории портала: заменяет 8 позиционных аргументов, в которых легко перепутать
 * {@code actorId}/{@code targetUserId} и {@code oldValue}/{@code newValue}.
 */
public record PortalHistoryRecordCommand(
        Long portalId,
        PortalHistoryEvent event,
        Long actorId,
        Long targetUserId,
        String reason,
        String fieldName,
        String oldValue,
        String newValue) {
}

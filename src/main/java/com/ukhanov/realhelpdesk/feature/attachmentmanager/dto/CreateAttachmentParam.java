package com.ukhanov.realhelpdesk.feature.attachmentmanager.dto;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;

/**
 * Параметры создания сущности вложения: сообщение, автор и метаданные файла. Заменяет 6 позиционных аргументов
 * MapStruct-фабрики.
 */
public record CreateAttachmentParam(
        MessageModel message,
        UserModel uploadedBy,
        String fileName,
        String storageKey,
        String contentType,
        long sizeBytes) {
}

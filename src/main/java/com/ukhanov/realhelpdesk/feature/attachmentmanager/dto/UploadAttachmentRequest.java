package com.ukhanov.realhelpdesk.feature.attachmentmanager.dto;

import jakarta.validation.constraints.Size;

import com.ukhanov.realhelpdesk.core.annotation.NoHtml;

public record UploadAttachmentRequest(
        @Size(max = 8000, message = "Сообщение не может быть больше 8000 символов") @NoHtml String messageText) {
}

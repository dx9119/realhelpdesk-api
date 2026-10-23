package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.CreateAttachmentParam;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.mapper.AttachmentMapper;

/**
 * Изоляция MapStruct-маппера и {@link StaticProperties}: сервисы вложений не зависят от деталей сборки downloadUrl.
 */
@Service
public class AttachmentMappingService {

    private final AttachmentMapper attachmentMapper;
    private final StaticProperties staticProperties;

    public AttachmentMappingService(AttachmentMapper attachmentMapper, StaticProperties staticProperties) {
        this.attachmentMapper = Objects.requireNonNull(attachmentMapper, "attachmentMapper must not be null");
        this.staticProperties = Objects.requireNonNull(staticProperties, "staticProperties must not be null");
    }

    public AttachmentModel toEntity(CreateAttachmentParam param) {
        return attachmentMapper.toEntity(param);
    }

    public AttachmentResponse toResponse(AttachmentModel attachment, Long portalId, Long ticketId) {
        return attachmentMapper.toResponse(attachment, portalId, ticketId, staticProperties);
    }
}

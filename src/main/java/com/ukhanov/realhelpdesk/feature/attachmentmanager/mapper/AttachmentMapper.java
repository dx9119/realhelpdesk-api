package com.ukhanov.realhelpdesk.feature.attachmentmanager.mapper;

import java.util.Objects;

import org.mapstruct.BeanMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.CreateAttachmentParam;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AttachmentMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "message", source = "message")
    @Mapping(target = "uploadedBy", source = "uploadedBy")
    @Mapping(target = "fileName", source = "fileName")
    @Mapping(target = "storageKey", source = "storageKey")
    @Mapping(target = "contentType", source = "contentType")
    @Mapping(target = "sizeBytes", source = "sizeBytes")
    AttachmentModel toEntity(CreateAttachmentParam param);

    // staticProperties передаётся через @Context, а не внедряется в бин: маппер собирает downloadUrl относительно
    // публичного origin раздачи, и один и тот же бин работает с разным base-url в dev и prod
    @Mapping(target = "ticketId", source = "ticketId")
    @Mapping(target = "messageId", source = "attachment.message.id")
    @Mapping(target = "uploadedById", source = "attachment.uploadedBy.id")
    @Mapping(target = "uploadedByFullName", source = "attachment.uploadedBy", qualifiedByName = "authorFullName")
    @Mapping(target = "downloadUrl",
            expression = "java(downloadUrl(staticProperties, portalId, ticketId, attachment.getId()))")
    AttachmentResponse toResponse(AttachmentModel attachment, Long portalId, Long ticketId,
            @Context StaticProperties staticProperties);

    @Named("authorFullName")
    default String authorFullName(UserModel uploadedBy) {
        return uploadedBy != null ? uploadedBy.getLastName() + " " + uploadedBy.getFirstName() : "Неизвестный автор";
    }

    default String downloadUrl(StaticProperties staticProperties, Long portalId, Long ticketId, Long attachmentId) {
        Objects.requireNonNull(staticProperties, "staticProperties");
        String path = "/api/v1/portals/" + portalId + "/tickets/" + ticketId + "/attachments/" + attachmentId;
        String baseUrl = staticProperties.getBaseUrl();
        return baseUrl != null ? baseUrl + path : path;
    }

}

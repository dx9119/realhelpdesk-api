package com.ukhanov.realhelpdesk.feature.notificationmanager.mapper;

import org.mapstruct.Mapper;

import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationResponse;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationResponse toResponse(NotificationModel model);

}

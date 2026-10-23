package com.ukhanov.realhelpdesk.feature.messagemanager.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.MessageResponse;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MessageMapper {

    // author и ticket — отдельные параметры: их проставляет сервис после проверки доступа, из запроса они не берутся
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "messageText", source = "request.messageText")
    @Mapping(target = "author", source = "author")
    @Mapping(target = "ticket", source = "ticket")
    MessageModel toEntity(CreateMessageRequest request, UserModel author, TicketModel ticket);

    @Mapping(target = "ticketId", source = "ticket.id")
    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "authorFullName", source = "author", qualifiedByName = "authorFullName")
    MessageResponse toResponse(MessageModel model);

    @Named("authorFullName")
    default String authorFullName(UserModel author) {
        return author != null ? author.getLastName() + " " + author.getFirstName() : "Неизвестный автор";
    }

}

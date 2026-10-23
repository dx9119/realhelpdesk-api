package com.ukhanov.realhelpdesk.feature.ticketmanager.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.TicketResponse;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TicketMapper {

    // ticketStatus зафиксирован в constant: клиент не может создать заявку сразу закрытой
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "title", source = "request.title")
    @Mapping(target = "body", source = "request.body")
    @Mapping(target = "author", source = "author")
    @Mapping(target = "portal", source = "portal")
    @Mapping(target = "ticketStatus", constant = "OPEN")
    @Mapping(target = "ticketPriority", source = "request.ticketPriority", defaultValue = "NONE")
    @Mapping(target = "accessStatus", source = "request.ticketAccessStatus", defaultValue = "CREATOR_AND_PORTAL_USERS")
    TicketModel fromRequest(CreateTicketRequest request, UserModel author, PortalModel portal);

    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "authorFullName", source = "author", qualifiedByName = "authorFullName")
    @Mapping(target = "portalName", source = "portal.name")
    @Mapping(target = "portalId", source = "portal.id")
    @Mapping(target = "ticketAccessStatus", source = "accessStatus")
    TicketResponse toResponse(TicketModel model);

    @Named("authorFullName")
    default String authorFullName(UserModel author) {
        return author != null ? author.getLastName() + " " + author.getFirstName() : "Неизвестный автор";
    }

}

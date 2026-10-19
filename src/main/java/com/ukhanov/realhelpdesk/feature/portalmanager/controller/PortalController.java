package com.ukhanov.realhelpdesk.feature.portalmanager.controller;

import java.io.UnsupportedEncodingException;
import java.net.URI;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.DeleteResult;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PatchPortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalBulkDeleteRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalInfoResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalSettingsResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalUsersRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalVisibilityRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalVisibilityResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.UpdatePortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.portalmanager.service.PortalManageService;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * Пути порталов: {@code /portals/{id}} — информация о портале, {@code /portals/{id}/settings} — его настройки, {@code /portals/accessible}
 * — порталы, доступные пользователю.
 *
 * <p>
 * Раньше настройки жили под {@code /portals/shared/...} («shared» читалось как отдельная сущность, а не как настройки того же портала), а
 * массовое удаление — в query {@code DELETE /portals?ids=}.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals")
public class PortalController {

    private final PortalManageService portalManageService;

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<CreatePortalResponse> createPortal(@Valid @RequestBody CreatePortalRequest createPortalRequest)
            throws PortalException, LimitException, UserManageException, MessagingException, UnsupportedEncodingException {
        CreatePortalResponse response = portalManageService.createPortal(createPortalRequest);
        return ResponseEntity.created(URI.create("/api/v1/portals/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<PortalResponse> getPagedPortals(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        return portalManageService.getPagePortalsByOwner(page, size, sortBy, order);
    }

    /** Порталы, к которым у пользователя есть доступ (было {@code /portals/shared}). */
    @GetMapping("/accessible")
    public PageResponse<PortalResponse> getPagePortalsByAccess(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        return portalManageService.getPagePortalsByAccess(page, size, sortBy, order);
    }

    @GetMapping("/ids")
    public ResponseEntity<ItemsResponse<Long>> getPortalIds() {
        return ResponseEntity.ok(ItemsResponse.of(portalManageService.mapAccessiblePortalsToIds()));
    }

    @GetMapping("/info")
    public ResponseEntity<ItemsResponse<PortalInfoResponse>> getPortalInfos() throws PortalException {
        return ResponseEntity.ok(ItemsResponse.of(portalManageService.mapAccessiblePortalsToInfo()));
    }

    @PreAuthorize("@accessValidationService.hasPortalAccess(#portalId)")
    @GetMapping("/{portalId}")
    public ResponseEntity<PortalInfoResponse> getPortalInfo(@PathVariable @NotNull Long portalId) throws PortalException {
        PortalInfoResponse info = portalManageService.getPortalInfo(portalId);
        return ResponseEntity.ok().eTag(EntityTag.of("portal", info.version())).body(info);
    }

    @PreAuthorize("@accessValidationService.hasPortalManageAccess(#portalId)")
    @PutMapping("/{portalId}")
    public ResponseEntity<PortalInfoResponse> updatePortalInfo(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody UpdatePortalInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws PortalException {

        PortalInfoResponse response = portalManageService.updatePortalInfo(portalId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("portal", response.version())).body(response);
    }

    /** PATCH: меняет только переданные поля; If-Match с версией из GET защищает от потери чужого изменения. */
    @PreAuthorize("@accessValidationService.hasPortalManageAccess(#portalId)")
    @PatchMapping("/{portalId}")
    public ResponseEntity<PortalInfoResponse> patchPortalInfo(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PatchPortalInfoRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws PortalException {

        PortalInfoResponse response = portalManageService.patchPortalInfo(portalId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("portal", response.version())).body(response);
    }

    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @GetMapping("/{portalId}/settings/visibility")
    public ResponseEntity<PortalVisibilityResponse> getPortalVisibility(@PathVariable @NotNull Long portalId) throws PortalException {
        return ResponseEntity.ok(new PortalVisibilityResponse(portalManageService.getStatusPortal(portalId)));
    }

    /** Флаг видимости — команда, поэтому 204 без тела (см. docs/api.md, раздел о кодах). */
    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @PutMapping("/{portalId}/settings/visibility")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> setPortalVisibility(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalVisibilityRequest request) throws PortalException {
        portalManageService.setPortalStatus(portalId, request.isPublic());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@accessValidationService.hasPortalManageAccess(#portalId)")
    @GetMapping("/{portalId}/settings")
    public ResponseEntity<PortalSettingsResponse> getPortalSettings(@PathVariable @NotNull Long portalId) throws PortalException {
        return ResponseEntity.ok(portalManageService.getPortalSettings(portalId));
    }

    /** Полная замена списка пользователей — изменение ресурса, поэтому 200 с актуальными настройками. */
    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @PutMapping("/{portalId}/settings/users")
    public ResponseEntity<PortalSettingsResponse> setPortalUsers(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalUsersRequest request) throws PortalException, LimitException {
        portalManageService.addUserForPortal(portalId, request.userIds());
        return ResponseEntity.ok(portalManageService.getPortalSettings(portalId));
    }

    /** Массовое удаление: набор id в теле, ответ — сколько удалено и какие id (было {@code DELETE /portals?ids=}). */
    @PostMapping("/bulk-delete")
    public ResponseEntity<DeleteResult> deletePortals(@Valid @RequestBody PortalBulkDeleteRequest request) throws PortalException {
        DeleteResult response = portalManageService.deletePortals(request.ids());
        return ResponseEntity.ok(response);
    }

}

package com.ukhanov.realhelpdesk.feature.portalmanager.controller;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.ratelimit.annotation.RateLimit;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalHistoryResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferConfirmRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRejectRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;
import com.ukhanov.realhelpdesk.feature.portalmanager.service.PortalManageService;
import com.ukhanov.realhelpdesk.feature.portalmanager.service.PortalOwnerTransferService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * Передача владения порталом и история портала. Подтверждение и отклонение делает предлагаемый владелец — у него ещё нет доступа к порталу,
 * поэтому авторизация этих операций проверяется в сервисе (пользователю без запроса отвечается 404, чтобы не раскрывать его существование).
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals")
public class PortalTransferController {

    private final PortalOwnerTransferService transferService;
    private final PortalManageService portalManageService;

    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @RateLimit(key = "portal-transfer-request")
    @PostMapping("/{portalId}/owner-transfer")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<PortalTransferResponse> initiate(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferRequest request) throws PortalException, LimitException {
        PortalTransferResponse response = transferService.initiate(portalId, request);
        return ResponseEntity.created(URI.create("/api/v1/portals/" + portalId + "/owner-transfer")).body(response);
    }

    /** Активный запрос: виден инициатору и предлагаемому владельцу (у того ещё нет доступа к порталу). */
    @GetMapping("/{portalId}/owner-transfer")
    public ResponseEntity<PortalTransferResponse> getPending(@PathVariable @NotNull Long portalId) throws PortalException {
        return ResponseEntity.ok(transferService.getPending(portalId));
    }

    @RateLimit(key = "portal-transfer-confirm")
    @PostMapping("/{portalId}/owner-transfer/confirm")
    public ResponseEntity<PortalTransferResponse> confirm(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferConfirmRequest request) throws PortalException, LimitException {
        return ResponseEntity.ok(transferService.confirm(portalId, request));
    }

    @PostMapping("/{portalId}/owner-transfer/reject")
    public ResponseEntity<PortalTransferResponse> reject(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferRejectRequest request) throws PortalException {
        return ResponseEntity.ok(transferService.reject(portalId, request));
    }

    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @PostMapping("/{portalId}/owner-transfer/cancel")
    public ResponseEntity<PortalTransferResponse> cancel(@PathVariable @NotNull Long portalId) throws PortalException {
        return ResponseEntity.ok(transferService.cancel(portalId));
    }

    @PreAuthorize("@accessValidationService.hasPortalManageAccess(#portalId)")
    @GetMapping("/{portalId}/history")
    public PageResponse<PortalHistoryResponse> getHistory(@PathVariable @NotNull Long portalId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy, @RequestParam(defaultValue = "desc") String order) {
        return portalManageService.getPortalHistory(portalId, page, size, sortBy, order);
    }
}

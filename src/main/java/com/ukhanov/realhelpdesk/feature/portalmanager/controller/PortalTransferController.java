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
import com.ukhanov.realhelpdesk.feature.portalmanager.service.PortalHistoryQueryService;
import com.ukhanov.realhelpdesk.feature.portalmanager.service.PortalOwnerTransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/portals")
public class PortalTransferController {

    private final PortalOwnerTransferService transferService;
    /** Чтение истории портала — отдельная читющая зона (см. PortalHistoryQueryService). */
    private final PortalHistoryQueryService historyQueryService;

    @Operation(summary = "Инициировать передачу владения",
            description = "Создаёт запрос на передачу портала другому пользователю. "
                    + "Одновременно на портал активен только один запрос — второй завершается 409.")
    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @RateLimit(key = "portal-transfer-request")
    @PostMapping("/{portalId}/owner-transfer")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<PortalTransferResponse> initiate(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferRequest request) throws PortalException, LimitException {
        PortalTransferResponse response = transferService.initiate(portalId, request);
        return ResponseEntity.created(URI.create("/api/v1/portals/" + portalId + "/owner-transfer")).body(response);
    }

    @Operation(summary = "Активный запрос на передачу",
            description = "Возвращает активный запрос на передачу владения; 404, если запроса нет.")
    @GetMapping("/{portalId}/owner-transfer")
    public ResponseEntity<PortalTransferResponse> getPending(@PathVariable @NotNull Long portalId)
            throws PortalException {
        return ResponseEntity.ok(transferService.getPending(portalId));
    }

    @Operation(summary = "Подтвердить передачу владения",
            description = "Принимает запрос: владелец меняется, новый владелец исключается из участников; "
                    + "старый остаётся участником при keepOldOwnerAsMember=true.")
    @RateLimit(key = "portal-transfer-confirm")
    @PostMapping("/{portalId}/owner-transfer/confirm")
    public ResponseEntity<PortalTransferResponse> confirm(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferConfirmRequest request) throws PortalException, LimitException {
        return ResponseEntity.ok(transferService.confirm(portalId, request));
    }

    @Operation(summary = "Отклонить передачу владения",
            description = "Отклоняет активный запрос; портал остаётся у текущего владельца.")
    @PostMapping("/{portalId}/owner-transfer/reject")
    public ResponseEntity<PortalTransferResponse> reject(@PathVariable @NotNull Long portalId,
            @Valid @RequestBody PortalTransferRejectRequest request) throws PortalException {
        return ResponseEntity.ok(transferService.reject(portalId, request));
    }

    @Operation(summary = "Отозвать запрос на передачу",
            description = "Отзывает активный запрос до того, как по нему принято решение.")
    @PreAuthorize("@accessValidationService.hasPortalOwner(#portalId)")
    @PostMapping("/{portalId}/owner-transfer/cancel")
    public ResponseEntity<PortalTransferResponse> cancel(@PathVariable @NotNull Long portalId) throws PortalException {
        return ResponseEntity.ok(transferService.cancel(portalId));
    }

    @Operation(summary = "История портала",
            description = "События передачи владения и изменения портала (переименование, описание, "
                    + "видимость, участники, удаление). Пагинированно.")
    @PreAuthorize("@accessValidationService.hasPortalManageAccess(#portalId)")
    @GetMapping("/{portalId}/history")
    public PageResponse<PortalHistoryResponse> getHistory(@PathVariable @NotNull Long portalId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        return historyQueryService.getPortalHistory(portalId, page, size, sortBy, order);
    }
}

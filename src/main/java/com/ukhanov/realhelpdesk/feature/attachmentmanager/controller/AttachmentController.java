package com.ukhanov.realhelpdesk.feature.attachmentmanager.controller;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.mail.MessagingException;
import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.FileDownload;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.UploadAttachmentRequest;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.service.AttachmentManageService;
import com.ukhanov.realhelpdesk.feature.messagemanager.exception.MessageException;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/portals/{portalId}/tickets/{ticketId}/attachments")
public class AttachmentController {

    private static final String BYTES_UNIT = "bytes";

    private final AttachmentManageService attachmentManageService;
    private final StaticProperties staticProperties;

    public AttachmentController(AttachmentManageService attachmentManageService, StaticProperties staticProperties) {
        this.attachmentManageService = attachmentManageService;
        this.staticProperties = Objects.requireNonNull(staticProperties, "staticProperties must not be null");
    }

    @Operation(summary = "Загрузка вложения",
            description = "Загружает файл в MinIO, привязанный к заявке. Создаёт сообщение с файлом. "
                    + "Location — downloadUrl (абсолютный при заданном static.base-url).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<AttachmentResponse> uploadAttachment(@Valid @ModelAttribute UploadAttachmentRequest form,
            @RequestParam("file") MultipartFile file, @PathVariable Long portalId, @PathVariable Long ticketId)
            throws AttachmentException, TicketException, MessageException, MessagingException,
            UnsupportedEncodingException {
        AttachmentResponse response = attachmentManageService.uploadAttachment(file, form.messageText(), portalId,
                ticketId);
        return ResponseEntity.created(URI.create(response.downloadUrl())).body(response);
    }

    @Operation(summary = "Список вложений", description = "Возвращает метаданные всех вложений заявки.")
    @GetMapping
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    public ResponseEntity<ItemsResponse<AttachmentResponse>> getAttachments(@PathVariable Long portalId,
            @PathVariable Long ticketId)
            throws AttachmentException, TicketException {
        return ResponseEntity.ok(ItemsResponse.of(attachmentManageService.getAttachments(portalId, ticketId)));
    }

    @Operation(summary = "Скачивание вложения (Range)",
            description = "Отдаёт файл потоком с поддержкой HTTP Range (200/206/416). "
                    + "ETag неизменяем по id вложения. If-Range с ETag продолжает докачку (206), "
                    + "несовпавший валидатор — отдаёт файл целиком (200).")
    @GetMapping("/{attachmentId}")
    @PreAuthorize("@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)")
    @ApiResponse(responseCode = "200", description = "Файл целиком",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Часть файла (Range)",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "416", description = "Диапазон не может быть удовлетворён (Range)",
            content = @Content())
    public ResponseEntity<StreamingResponseBody> downloadAttachment(@PathVariable Long portalId,
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) throws AttachmentException {
        String etag = EntityTag.ofImmutable("attachment", attachmentId);
        boolean rangeAllowed = EntityTag.matches(ifRange, etag);

        FileDownload download = attachmentManageService.downloadAttachment(portalId, ticketId, attachmentId,
                rangeAllowed ? rangeHeader : null);

        if (download.isUnsatisfiable()) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .eTag(etag)
                    .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                    .header(HttpHeaders.CONTENT_RANGE, download.range().contentRange(download.sizeBytes()))
                    .header(HttpHeaders.CACHE_CONTROL, staticProperties.getCacheControl())
                    .build();
        }

        boolean partial = download.isPartial();
        InputStream content = download.content();
        StreamingResponseBody body = output -> {
            try (content) {
                content.transferTo(output);
            }
        };

        ResponseEntity.BodyBuilder response = partial
                ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                : ResponseEntity.ok();
        response.eTag(etag)
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, staticProperties.getCacheControl())
                .contentType(mediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_LENGTH,
                        String.valueOf(partial ? download.range().length() : download.sizeBytes()));

        if (partial) {
            response.header(HttpHeaders.CONTENT_RANGE, download.range().contentRange(download.sizeBytes()));
        }
        return response.body(body);
    }

    private MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            // Битые метаданные вложения: файл отдаём, но в логе должно быть видно, почему octet-stream
            logger.warn("Некорректный content-type вложения «{}», отдаю application/octet-stream",
                    LogSanitizer.value(contentType));
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}

package com.ukhanov.realhelpdesk.feature.attachmentmanager.controller;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.http.RangeHeader;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.FileDownload;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.service.AttachmentManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты REST-контракта контроллера вложений (AttachmentController)")
class AttachmentControllerTest {

    private static final Long PORTAL_ID = 5L;
    private static final Long TICKET_ID = 77L;
    private static final Long ATTACHMENT_ID = 15L;
    private static final String UPLOAD_PATH = "/api/v1/portals/{portalId}/tickets/{ticketId}/attachments";
    private static final byte[] FILE_CONTENT = "file-content".getBytes(StandardCharsets.UTF_8);

    @Mock
    private AttachmentManageService mockAttachmentManageService;

    private MockMvcTester mockMvc;
    private StaticProperties staticProperties;

    @BeforeEach
    void setUp() throws Exception {
        staticProperties = new StaticProperties();
        mockMvc = MockMvcTester.of(List.of(new AttachmentController(mockAttachmentManageService, staticProperties)),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    // ────────────────────────────────────────────────
    // Загрузка: 201 + Location
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("POST .../attachments (multipart) → 201 + Location на вложение")
    void uploadAttachment_validMultipart_returnsCreatedWithLocation() throws Exception {
        // given
        when(mockAttachmentManageService.uploadAttachment(any(MockMultipartFile.class), eq("Вот отчёт"), eq(PORTAL_ID), eq(TICKET_ID)))
                .thenReturn(attachmentResponse());
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT);

        // when
        MvcTestResult result = mockMvc.post().uri(UPLOAD_PATH, PORTAL_ID, TICKET_ID).multipart().file(file)
                .param("messageText", "Вот отчёт").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/api/v1/portals/5/tickets/77/attachments/15");
        assertThat(result).bodyJson().extractingPath("$.id").asNumber().isEqualTo(15);
        assertThat(result).bodyJson().extractingPath("$.fileName").asString().isEqualTo("report.pdf");
        assertThat(result).bodyJson().extractingPath("$.sizeBytes").asNumber().isEqualTo(FILE_CONTENT.length);
        assertThat(result).bodyJson().extractingPath("$.downloadUrl").asString().isEqualTo("/api/v1/portals/5/tickets/77/attachments/15");
    }

    @Test
    @DisplayName("Location повторяет downloadUrl: при заданном static.base-url — абсолютный origin раздачи")
    void uploadAttachment_absoluteDownloadUrl_returnsSameLocation() throws Exception {
        // given
        AttachmentResponse response = attachmentResponse("https://cdn.example.com/api/v1/portals/5/tickets/77/attachments/15");
        when(mockAttachmentManageService.uploadAttachment(any(MockMultipartFile.class), eq("Вот отчёт"), eq(PORTAL_ID), eq(TICKET_ID)))
                .thenReturn(response);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT);

        // when
        MvcTestResult result = mockMvc.post().uri(UPLOAD_PATH, PORTAL_ID, TICKET_ID).multipart().file(file)
                .param("messageText", "Вот отчёт").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location",
                "https://cdn.example.com/api/v1/portals/5/tickets/77/attachments/15");
    }

    @Test
    @DisplayName("POST .../attachments без части file → 400 (problem+json), сервис не вызывается")
    void uploadAttachment_missingFilePart_returnsBadRequestProblem() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.post().uri(UPLOAD_PATH, PORTAL_ID, TICKET_ID).multipart().param("messageText", "без файла")
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.title").asString().isEqualTo("Bad Request");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Отсутствует обязательная часть запроса 'file'");

        verify(mockAttachmentManageService, never()).uploadAttachment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST .../attachments с текстом длиннее 8000 символов → 400 с ошибкой поля messageText")
    void uploadAttachment_tooLongMessageText_returnsValidationError() throws Exception {
        // given
        String longText = "а".repeat(8001);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT);

        // when
        MvcTestResult result = mockMvc.post().uri(UPLOAD_PATH, PORTAL_ID, TICKET_ID).multipart().file(file).param("messageText", longText)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().hasPath("$.errors.messageText");

        verify(mockAttachmentManageService, never()).uploadAttachment(any(), any(), any(), any());
    }

    // ────────────────────────────────────────────────
    // Список метаданных
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET .../attachments → 200 со списком метаданных")
    void getAttachments_existingAttachments_returnsList() throws Exception {
        // given
        when(mockAttachmentManageService.getAttachments(PORTAL_ID, TICKET_ID)).thenReturn(List.of(attachmentResponse()));

        // when
        MvcTestResult result = mockMvc.get().uri(UPLOAD_PATH, PORTAL_ID, TICKET_ID).exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].id").asNumber().isEqualTo(15);
        assertThat(result).bodyJson().extractingPath("$.items[0].fileName").asString().isEqualTo("report.pdf");
        assertThat(result).bodyJson().extractingPath("$.items[0].messageId").asNumber().isEqualTo(900);
        assertThat(result).bodyJson().extractingPath("$.items[0].uploadedById").asNumber().isEqualTo(42);
        assertThat(result).bodyJson().extractingPath("$.total").asNumber().isEqualTo(1);
    }

    // ────────────────────────────────────────────────
    // Отдача файла
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET .../attachments/{id} → 200 с байтами, заголовками скачивания и Accept-Ranges")
    void downloadAttachment_noRange_returnsFileWithDownloadHeaders() throws Exception {
        // given
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null))
                .thenReturn(fullDownload("Отчёт за месяц.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT));

        // when
        MvcTestResult started = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID).asyncExchange();

        // then
        assertThat(started).hasStatusOk().hasHeader("Content-Type", MediaType.APPLICATION_PDF_VALUE)
                .hasHeader("Cache-Control", "private, no-store").hasHeader("Accept-Ranges", "bytes");
        assertThat(started.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("filename*=UTF-8''");
        assertThat(started.getResponse().getContentLengthLong()).isEqualTo(FILE_CONTENT.length);
        assertThat(started).request().hasAsyncStarted(true);

        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()));
        assertThat(resolved).hasStatusOk().body().isEqualTo(FILE_CONTENT);
    }

    @Test
    @DisplayName("Cache-Control берётся из static.cache-control: значение уходит в ответ как есть")
    void downloadAttachment_configuredCacheControl_returnsItAsIs() throws Exception {
        // given
        staticProperties.setCacheControl("public, max-age=300");
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null))
                .thenReturn(fullDownload("Отчёт.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT));

        // when
        MvcTestResult started = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID).asyncExchange();

        // then
        assertThat(started).hasStatusOk().hasHeader("Cache-Control", "public, max-age=300");
        assertThat(started).request().hasAsyncStarted(true);

        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()));
        assertThat(resolved).hasStatusOk().body().isEqualTo(FILE_CONTENT);
    }

    @Test
    @DisplayName("GET .../attachments/{id} c Range → 206, Content-Range и только запрошенные байты")
    void downloadAttachment_validRange_returnsPartialContent() throws Exception {
        // given
        byte[] fragment = "file-".getBytes(StandardCharsets.UTF_8);
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, "bytes=0-4"))
                .thenReturn(partialDownload("Отчёт.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT.length, 0, 4, fragment));

        // when
        MvcTestResult started = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID)
                .header("Range", "bytes=0-4").asyncExchange();

        // then
        assertThat(started).hasStatus(HttpStatus.PARTIAL_CONTENT).hasHeader("Content-Range", "bytes 0-4/" + FILE_CONTENT.length)
                .hasHeader("Accept-Ranges", "bytes");
        assertThat(started.getResponse().getContentLengthLong()).isEqualTo(fragment.length);
        assertThat(started).request().hasAsyncStarted(true);

        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()));
        assertThat(resolved).hasStatus(HttpStatus.PARTIAL_CONTENT).body().isEqualTo(fragment);
    }

    @Test
    @DisplayName("GET .../attachments/{id} c Range за концом файла → 416 с Content-Range «bytes * / size», поток не открывается")
    void downloadAttachment_unsatisfiableRange_returnsRequestedRangeNotSatisfiable() throws Exception {
        // given
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, "bytes=9999-"))
                .thenReturn(unsatisfiableDownload("Отчёт.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT.length));

        // when
        MvcTestResult result = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID)
                .header("Range", "bytes=9999-").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .hasHeader("Content-Range", "bytes */" + FILE_CONTENT.length).hasHeader("Cache-Control", "private, no-store")
                .hasHeader("Accept-Ranges", "bytes");
    }

    @Test
    @DisplayName("GET .../attachments/{id} c If-Range → Range игнорируется (валидаторов не отдаём), ответ 200 целиком")
    void downloadAttachment_withIfRange_ignoresRange() throws Exception {
        // given
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null))
                .thenReturn(fullDownload("Отчёт.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT));

        // when
        MvcTestResult started = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID)
                .header("Range", "bytes=0-4").header("If-Range", "\"abc\"").asyncExchange();

        // then
        assertThat(started).hasStatusOk();
        assertThat(started.getResponse().getHeader("Content-Range")).isNull();
        assertThat(started).request().hasAsyncStarted(true);

        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()));
        assertThat(resolved).hasStatusOk().body().isEqualTo(FILE_CONTENT);
        verify(mockAttachmentManageService).downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null);
    }

    @Test
    @DisplayName("GET .../attachments/{id} с несколькими диапазонами → сервис отдаёт FULL, ответ 200 целиком")
    void downloadAttachment_multipleRanges_fallsBackToFullResponse() throws Exception {
        // given
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, "bytes=0-1,5-6"))
                .thenReturn(fullDownload("Отчёт.pdf", MediaType.APPLICATION_PDF_VALUE, FILE_CONTENT));

        // when
        MvcTestResult started = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID)
                .header("Range", "bytes=0-1,5-6").asyncExchange();

        // then
        assertThat(started).hasStatusOk();
        assertThat(started.getResponse().getHeader("Content-Range")).isNull();
        assertThat(started).request().hasAsyncStarted(true);

        MvcTestResult resolved = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()));
        assertThat(resolved).hasStatusOk().body().isEqualTo(FILE_CONTENT);
    }

    @Test
    @DisplayName("GET .../attachments/{id} файла нет → 404 problem+json")
    void downloadAttachment_unknownAttachment_returnsNotFoundProblem() throws Exception {
        // given
        when(mockAttachmentManageService.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null))
                .thenThrow(AttachmentException.notFound("Файл не найден"));

        // when
        MvcTestResult result = mockMvc.get().uri(UPLOAD_PATH + "/{attachmentId}", PORTAL_ID, TICKET_ID, ATTACHMENT_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(404);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Файл не найден");
    }

    // ────────────────────────────────────────────────
    // Вспомогательные фабрики
    // ────────────────────────────────────────────────

    private FileDownload fullDownload(String fileName, String contentType, byte[] content) {
        return new FileDownload(fileName, contentType, content.length, RangeHeader.parse(null, content.length),
                new ByteArrayInputStream(content));
    }

    private FileDownload partialDownload(String fileName, String contentType, long totalSize, long start, long end, byte[] content) {
        return new FileDownload(fileName, contentType, totalSize, RangeHeader.parse("bytes=" + start + "-" + end, totalSize),
                new ByteArrayInputStream(content));
    }

    private FileDownload unsatisfiableDownload(String fileName, String contentType, long totalSize) {
        return new FileDownload(fileName, contentType, totalSize, RangeHeader.parse("bytes=9999-", totalSize), null);
    }

    private AttachmentResponse attachmentResponse() {
        return attachmentResponse("/api/v1/portals/5/tickets/77/attachments/15");
    }

    private AttachmentResponse attachmentResponse(String downloadUrl) {
        return new AttachmentResponse(ATTACHMENT_ID, 900L, TICKET_ID, "report.pdf", MediaType.APPLICATION_PDF_VALUE,
                (long) FILE_CONTENT.length, 42L, "Иванов Иван", Instant.now(), downloadUrl);
    }
}

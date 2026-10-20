package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import jakarta.mail.MessagingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.core.observability.BusinessMetrics;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.storage.exception.StorageException;
import com.ukhanov.realhelpdesk.core.storage.service.MinioStorageService;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.domain.attachment.service.AttachmentDomainService;
import com.ukhanov.realhelpdesk.domain.message.model.MessageModel;
import com.ukhanov.realhelpdesk.domain.message.service.MessageDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.FileDownload;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.mapper.AttachmentMapper;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageRequest;
import com.ukhanov.realhelpdesk.feature.messagemanager.dto.CreateMessageResponse;
import com.ukhanov.realhelpdesk.feature.messagemanager.service.MessageManageService;
import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса вложений AttachmentManageService")
class AttachmentManageServiceTest {

    private static final Long PORTAL_ID = 5L;
    private static final Long OTHER_PORTAL_ID = 6L;
    private static final Long TICKET_ID = 77L;
    private static final Long MESSAGE_ID = 900L;
    private static final Long ATTACHMENT_ID = 15L;
    private static final String STORAGE_KEY = "tickets/77/2f0b0e8e.pdf";
    private static final byte[] CONTENT = "file-content".getBytes(StandardCharsets.UTF_8);

    @Mock
    private MinioStorageService mockStorageService;
    @Mock
    private AttachmentDomainService mockAttachmentDomainService;
    @Mock
    private MessageDomainService mockMessageDomainService;
    @Mock
    private MessageManageService mockMessageManageService;
    @Mock
    private TicketDomainService mockTicketDomainService;
    @Mock
    private CurrentUserProvider mockCurrentUserProvider;

    @Captor
    private ArgumentCaptor<String> keyCaptor;
    @Captor
    private ArgumentCaptor<CreateMessageRequest> requestCaptor;

    private AttachmentManageService service;
    private final AttachmentMapper attachmentMapper = Mappers.getMapper(AttachmentMapper.class);
    private final StaticProperties staticProperties = new StaticProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new AttachmentManageService(mockStorageService, mockAttachmentDomainService, mockMessageDomainService,
                mockMessageManageService, mockTicketDomainService, mockCurrentUserProvider, attachmentMapper, staticProperties,
                new BusinessMetrics(meterRegistry));
    }

    @Test
    @DisplayName("Конструктор без настройки статики — ошибка, а не NPE в момент сборки URL")
    void constructor_nullStaticProperties_throwsNullPointerException() {

        // given / when / then
        assertThatThrownBy(() -> new AttachmentManageService(mockStorageService, mockAttachmentDomainService, mockMessageDomainService,
                mockMessageManageService, mockTicketDomainService, mockCurrentUserProvider, attachmentMapper, null,
                new BusinessMetrics(meterRegistry))).isInstanceOf(NullPointerException.class).hasMessageContaining("staticProperties");
    }

    // ────────────────────────────────────────────────
    // uploadAttachment — успешный сценарий
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("uploadAttachment → файл в MinIO, отдельное сообщение в заявке и вложение в БД, ответ с downloadUrl")
    void uploadAttachment_validFile_savesToMinioMessageAndDb() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("report.pdf", "application/pdf");
        givenTicketInPortal();
        givenCurrentUser();
        when(mockMessageManageService.createMessage(any(CreateMessageRequest.class), eq(TICKET_ID), eq(PORTAL_ID)))
                .thenReturn(new CreateMessageResponse(MESSAGE_ID));
        when(mockMessageDomainService.getMessageById(MESSAGE_ID)).thenReturn(message());
        when(mockAttachmentDomainService.saveAttachment(any(AttachmentModel.class))).thenAnswer(invocation -> {
            AttachmentModel saved = invocation.getArgument(0);
            saved.setId(ATTACHMENT_ID);
            saved.setCreatedAt(Instant.now());
            return saved;
        });

        AttachmentResponse response = service.uploadAttachment(file, "Вот отчёт", PORTAL_ID, TICKET_ID);

        // then
        assertThat(response.id()).isEqualTo(ATTACHMENT_ID);
        assertThat(response.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.fileName()).isEqualTo("report.pdf");
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.sizeBytes()).isEqualTo((long) CONTENT.length);
        assertThat(response.uploadedByFullName()).isEqualTo("Иванов Иван");
        assertThat(response.downloadUrl()).isEqualTo("/api/v1/portals/5/tickets/77/attachments/15");
        assertThat(response.createdAt()).isNotNull();

        verify(mockStorageService).upload(keyCaptor.capture(), any(InputStream.class), eq((long) CONTENT.length), eq("application/pdf"));
        assertThat(keyCaptor.getValue()).startsWith("tickets/77/").endsWith(".pdf");

        verify(mockMessageManageService).createMessage(requestCaptor.capture(), eq(TICKET_ID), eq(PORTAL_ID));
        assertThat(requestCaptor.getValue().messageText()).isEqualTo("Вот отчёт");

        // Бизнес-метрики вложений: группа contentType, размер в байтах
        Counter uploaded = meterRegistry.find("attachments.uploaded").tags("content_type", "application").counter();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.count()).isEqualTo(1);
        DistributionSummary summary = meterRegistry.find("attachments.size").tags("content_type", "application").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.totalAmount()).isEqualTo(CONTENT.length);
        assertThat(summary.getId().getBaseUnit()).isEqualTo("bytes");
    }

    @Test
    @DisplayName("uploadAttachment → текст не передан → сообщение подписывается именем файла")
    void uploadAttachment_blankMessageText_defaultsToFileCaption() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("report.pdf", "application/pdf");
        givenTicketInPortal();
        givenCurrentUser();
        when(mockMessageManageService.createMessage(any(CreateMessageRequest.class), eq(TICKET_ID), eq(PORTAL_ID)))
                .thenReturn(new CreateMessageResponse(MESSAGE_ID));
        when(mockMessageDomainService.getMessageById(MESSAGE_ID)).thenReturn(message());
        when(mockAttachmentDomainService.saveAttachment(any(AttachmentModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.uploadAttachment(file, "   ", PORTAL_ID, TICKET_ID);

        // then
        verify(mockMessageManageService).createMessage(requestCaptor.capture(), eq(TICKET_ID), eq(PORTAL_ID));
        assertThat(requestCaptor.getValue().messageText()).isEqualTo("Файл: report.pdf");
    }

    @Test
    @DisplayName("uploadAttachment → имя с путём и чужой Content-Type → имя вычищено, тип приведён к octet-stream")
    void uploadAttachment_untrustedFileNameAndContentType_isSanitized() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("C:\\fakepath\\report.PDF", "text/html; charset=utf-8");
        givenTicketInPortal();
        givenCurrentUser();
        when(mockMessageManageService.createMessage(any(CreateMessageRequest.class), eq(TICKET_ID), eq(PORTAL_ID)))
                .thenReturn(new CreateMessageResponse(MESSAGE_ID));
        when(mockMessageDomainService.getMessageById(MESSAGE_ID)).thenReturn(message());
        when(mockAttachmentDomainService.saveAttachment(any(AttachmentModel.class))).thenAnswer(invocation -> {
            AttachmentModel saved = invocation.getArgument(0);
            saved.setId(ATTACHMENT_ID);
            return saved;
        });

        AttachmentResponse response = service.uploadAttachment(file, null, PORTAL_ID, TICKET_ID);

        // then
        assertThat(response.fileName()).isEqualTo("report.PDF");
        assertThat(response.contentType()).isEqualTo("application/octet-stream");
        verify(mockStorageService).upload(keyCaptor.capture(), any(InputStream.class), anyLong(), eq("application/octet-stream"));
        assertThat(keyCaptor.getValue()).endsWith(".pdf");
    }

    // ────────────────────────────────────────────────
    // uploadAttachment — отказы
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("uploadAttachment → пустой файл → 400, ничего не сохраняется")
    void uploadAttachment_emptyFile_throwsBadRequest() {

        // given
        // when
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        AttachmentException exception = catchThrowableOfType(AttachmentException.class,
                () -> service.uploadAttachment(empty, null, PORTAL_ID, TICKET_ID));

        // then
        assertThat(exception.getStatus().value()).isEqualTo(400);
        verifyNoInteractions(mockStorageService, mockMessageManageService, mockAttachmentDomainService);
    }

    @Test
    @DisplayName("uploadAttachment → заявка другого портала → 404, в хранилище не пишем")
    void uploadAttachment_foreignTicket_throwsNotFoundWithoutStorageWrite() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("report.pdf", "application/pdf");
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket(OTHER_PORTAL_ID));

        // then
        assertThatThrownBy(() -> service.uploadAttachment(file, null, PORTAL_ID, TICKET_ID)).isInstanceOf(TicketException.class);

        verifyNoInteractions(mockStorageService, mockMessageManageService);
    }

    @Test
    @DisplayName("uploadAttachment → хранилище недоступно → ошибка уходит наверх, сообщение не создаётся")
    void uploadAttachment_storageFailure_doesNotCreateMessage() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("report.pdf", "application/pdf");
        givenTicketInPortal();
        doThrow(new StorageException("MinIO down")).when(mockStorageService).upload(any(), any(), anyLong(), any());

        // then
        assertThatThrownBy(() -> service.uploadAttachment(file, null, PORTAL_ID, TICKET_ID)).isInstanceOf(StorageException.class);

        verifyNoInteractions(mockMessageManageService);
    }

    @Test
    @DisplayName("uploadAttachment → сообщение не создалось → объект в MinIO удаляется (осиротевшего файла не остаётся)")
    void uploadAttachment_messageFailure_removesUploadedObject() throws Exception {

        // given
        // when
        MockMultipartFile file = mockFile("report.pdf", "application/pdf");
        givenTicketInPortal();
        when(mockMessageManageService.createMessage(any(CreateMessageRequest.class), eq(TICKET_ID), eq(PORTAL_ID)))
                .thenThrow(new MessagingException("smtp down"));

        // then
        assertThatThrownBy(() -> service.uploadAttachment(file, null, PORTAL_ID, TICKET_ID)).isInstanceOf(MessagingException.class);

        verify(mockStorageService).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("tickets/77/");
    }

    // ────────────────────────────────────────────────
    // getAttachments
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getAttachments → метаданные файлов заявки, заявка проверена на портал")
    void getAttachments_ticketWithAttachments_returnsMappedResponses() throws Exception {

        // given
        // when
        givenTicketInPortal();
        when(mockAttachmentDomainService.getAttachmentsForTicket(TICKET_ID, PORTAL_ID)).thenReturn(List.of(attachment()));

        List<AttachmentResponse> responses = service.getAttachments(PORTAL_ID, TICKET_ID);

        // then
        assertThat(responses).hasSize(1);
        AttachmentResponse response = responses.getFirst();
        assertThat(response.fileName()).isEqualTo("report.pdf");
        assertThat(response.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(response.downloadUrl()).isEqualTo("/api/v1/portals/5/tickets/77/attachments/15");
    }

    @Test
    @DisplayName("getAttachments → заявка чужого портала → 404, файлы не читаем")
    void getAttachments_foreignTicket_throwsNotFound() throws Exception {

        // given
        // when
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket(OTHER_PORTAL_ID));

        // then
        assertThatThrownBy(() -> service.getAttachments(PORTAL_ID, TICKET_ID)).isInstanceOf(TicketException.class);

        verifyNoInteractions(mockAttachmentDomainService);
    }

    // ────────────────────────────────────────────────
    // downloadAttachment
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("downloadAttachment без Range → метаданные из БД, байты — поток из MinIO целиком")
    void downloadAttachment_withoutRange_returnsMetadataAndFullStream() throws Exception {

        // given
        // when
        when(mockAttachmentDomainService.getAttachmentForTicket(ATTACHMENT_ID, TICKET_ID, PORTAL_ID)).thenReturn(attachment());
        when(mockStorageService.download(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(CONTENT));

        FileDownload download = service.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null);

        // then
        assertThat(download.fileName()).isEqualTo("report.pdf");
        assertThat(download.contentType()).isEqualTo("application/pdf");
        assertThat(download.sizeBytes()).isEqualTo((long) CONTENT.length);
        assertThat(download.isPartial()).isFalse();
        assertThat(download.isUnsatisfiable()).isFalse();
        assertThat(download.content().readAllBytes()).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("downloadAttachment с Range → в MinIO читается только фрагмент [start, end]")
    void downloadAttachment_range_readsOnlyFragment() throws Exception {

        // given
        // when
        when(mockAttachmentDomainService.getAttachmentForTicket(ATTACHMENT_ID, TICKET_ID, PORTAL_ID)).thenReturn(attachment());
        when(mockStorageService.download(STORAGE_KEY, 3, 5)).thenReturn(new ByteArrayInputStream(new byte[5]));

        FileDownload download = service.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, "bytes=3-7");

        // then
        assertThat(download.isPartial()).isTrue();
        assertThat(download.range().start()).isEqualTo(3);
        assertThat(download.range().end()).isEqualTo(7);
        assertThat(download.range().length()).isEqualTo(5);
        verify(mockStorageService).download(STORAGE_KEY, 3, 5);
        verify(mockStorageService, never()).download(STORAGE_KEY);
    }

    @Test
    @DisplayName("downloadAttachment с Range за концом файла → 416-результат, соединение с MinIO не открывается")
    void downloadAttachment_unsatisfiableRange_opensNoStream() throws Exception {

        // given
        // when
        when(mockAttachmentDomainService.getAttachmentForTicket(ATTACHMENT_ID, TICKET_ID, PORTAL_ID)).thenReturn(attachment());

        FileDownload download = service.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, "bytes=999-");

        // then
        assertThat(download.isUnsatisfiable()).isTrue();
        assertThat(download.content()).isNull();
        assertThat(download.range().contentRange(CONTENT.length)).isEqualTo("bytes */" + CONTENT.length);
        verifyNoInteractions(mockStorageService);
    }

    @Test
    @DisplayName("downloadAttachment → файла нет в заявке → 404, в хранилище не обращаемся")
    void downloadAttachment_unknownAttachment_throwsNotFound() throws Exception {

        // given
        // when
        when(mockAttachmentDomainService.getAttachmentForTicket(ATTACHMENT_ID, TICKET_ID, PORTAL_ID))
                .thenThrow(AttachmentException.notFound("Файл не найден"));

        AttachmentException exception = catchThrowableOfType(AttachmentException.class,
                () -> service.downloadAttachment(PORTAL_ID, TICKET_ID, ATTACHMENT_ID, null));

        // then
        assertThat(exception.getStatus().value()).isEqualTo(404);
        verifyNoInteractions(mockStorageService);
    }

    // ────────────────────────────────────────────────
    // Вспомогательные фабрики
    // ────────────────────────────────────────────────

    private MockMultipartFile mockFile(String originalFileName, String contentType) {
        return new MockMultipartFile("file", originalFileName, contentType, CONTENT);
    }

    private void givenTicketInPortal() throws TicketException {
        when(mockTicketDomainService.findTicketById(TICKET_ID)).thenReturn(ticket(PORTAL_ID));
    }

    private void givenCurrentUser() {
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(user());
    }

    private TicketModel ticket(Long portalId) {
        PortalModel portal = new PortalModel();
        portal.setId(portalId);

        TicketModel ticket = new TicketModel();
        ticket.setId(TICKET_ID);
        ticket.setPortal(portal);
        return ticket;
    }

    private UserModel user() {
        UserModel user = new UserModel();
        user.setId(77L);
        user.setFirstName("Иван");
        user.setLastName("Иванов");
        return user;
    }

    private MessageModel message() {
        MessageModel message = new MessageModel();
        message.setId(MESSAGE_ID);
        message.setTicket(ticket(PORTAL_ID));
        message.setAuthor(user());
        message.setMessageText("Файл: report.pdf");
        message.setCreatedAt(Instant.now());
        return message;
    }

    private AttachmentModel attachment() {
        AttachmentModel attachment = new AttachmentModel();
        attachment.setId(ATTACHMENT_ID);
        attachment.setMessage(message());
        attachment.setUploadedBy(user());
        attachment.setFileName("report.pdf");
        attachment.setStorageKey(STORAGE_KEY);
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes((long) CONTENT.length);
        attachment.setCreatedAt(Instant.now());
        return attachment;
    }
}

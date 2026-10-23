package com.ukhanov.realhelpdesk.core.observability;

import java.util.Map;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailLogService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailPolicyService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.TicketAccessValidationService;
import com.ukhanov.realhelpdesk.core.security.ratelimit.config.RateLimitProperties;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.core.storage.config.MinioProperties;
import com.ukhanov.realhelpdesk.core.storage.exception.StorageException;
import com.ukhanov.realhelpdesk.core.storage.service.MinioStorageService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketModel;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;
import com.ukhanov.realhelpdesk.domain.ticket.service.TicketDomainService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketRequest;
import com.ukhanov.realhelpdesk.feature.ticketmanager.dto.CreateTicketResponse;
import com.ukhanov.realhelpdesk.feature.ticketmanager.mapper.TicketMapper;
import com.ukhanov.realhelpdesk.feature.ticketmanager.service.TicketManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

/**
 * Ручные Observation: имена наблюдений на границах (ticket.create, attachment.upload, mail.send), low-cardinality теги
 * и отсутствие запрещённых ключей (id, email) в low-cardinality наборе.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Ручные наблюдения: ticket.create, attachment.upload, mail.send")
class ManualObservationTest {

    private static final Long PORTAL_ID = 100L;
    private static final Long USER_ID = 77L;
    private static final Long TICKET_ID = 42L;

    private TestObservationRegistry observationRegistry;

    @Mock
    private TicketDomainService ticketDomainService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private PortalDomainService portalDomainService;
    @Mock
    private PaginationAdapter paginationAdapter;
    @Mock
    private EmailDeliveryService emailDeliveryService;
    @Mock
    private TicketAccessValidationService ticketAccessValidationService;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private NotificationPublisher notificationPublisher;

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
    }

    @Test
    @DisplayName("ticket.create: наблюдение создаётся с low-тегом operation и без id/email в low-cardinality")
    void createTicket_validRequest_recordsTicketCreateObservation() throws Exception {

        // given
        // when
        TicketManageService service = new TicketManageService(ticketDomainService, currentUserProvider,
                portalDomainService,
                paginationAdapter, emailDeliveryService, ticketAccessValidationService, ticketRepository,
                emailTemplates,
                notificationPublisher, Mappers.getMapper(TicketMapper.class), observationRegistry,
                new BusinessMetrics(new SimpleMeterRegistry()));

        UserModel currentUser = new UserModel();
        currentUser.setId(USER_ID);
        currentUser.setEmail("test@user.com");
        when(currentUserProvider.getCurrentUserModel()).thenReturn(currentUser);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal(PORTAL_ID));
        when(ticketDomainService.saveTicket(any())).thenReturn(ticket(TICKET_ID));

        CreateTicketResponse response = service.createTicket(new CreateTicketRequest("New Issue", null, null, null),
                PORTAL_ID);

        // then
        assertThat(response.id()).isEqualTo(TICKET_ID);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("ticket.create")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("operation", "create")
                .hasHighCardinalityKeyValue("ticket.id", String.valueOf(TICKET_ID));
        assertNoForbiddenLowCardinalityKeys();
    }

    @Test
    @DisplayName("attachment.upload: наблюдение создаётся с low-тегом operation, "
            + "ключ объекта — только в high-cardinality")
    void uploadAttachment_unreachableMinio_recordsErrorObservation() {

        // given
        // when
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setAccessKey("minioadmin");
        properties.setSecretKey("secret");
        properties.setBucket("realhelpdesk-attachments");
        MinioStorageService storageService = new MinioStorageService(
                new io.minio.MinioClient.Builder().endpoint("http://minio:9000")
                        .credentials("minioadmin", "secret")
                        .build(),
                properties,
                observationRegistry);

        // ожидаемое исключение: MinIO недоступен в тестовом окружении — важно, что наблюдение с outcome=error записано
        assertThatThrownBy(() -> storageService.upload("tickets/42/file.pdf",
                new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/pdf"))
                .isInstanceOf(StorageException.class);

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("attachment.upload")
                .that()
                .hasBeenStarted()
                .hasLowCardinalityKeyValue("operation", "upload")
                .hasLowCardinalityKeyValue("outcome", "error");
        // then
        assertNoForbiddenLowCardinalityKeys();
    }

    @Test
    @DisplayName("mail.send: low-теги operation и event, адрес получателя не попадает ни в один тег")
    void sendEmail_validMessage_recordsMailSendObservation() throws Exception {

        // given
        // when
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
        EmailDeliveryService service = emailDeliveryService(mailSender);

        service.sendEmail("user@example.com", "Тема", "Текст", NotificationEvent.NEW_TICKET);

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("mail.send")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("operation", "send")
                .hasLowCardinalityKeyValue("event", "NEW_TICKET");
        // then
        assertNoForbiddenLowCardinalityKeys();
    }

    @Test
    @DisplayName("mail.send: ошибка SMTP → наблюдение остановлено с outcome=error (результат наблюдения)")
    void sendEmail_smtpFailure_recordsErrorOutcome() throws Exception {

        // given
        // when
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
        org.mockito.Mockito.doThrow(new MailSendException("Connection refused"))
                .when(mailSender)
                .send(any(MimeMessage.class));
        EmailDeliveryService service = emailDeliveryService(mailSender);

        // then
        assertThatThrownBy(() -> service.sendEmail("user@example.com", "Тема", "Текст", NotificationEvent.NEW_TICKET))
                .isInstanceOf(MailSendException.class);

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("mail.send")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("operation", "send")
                .hasLowCardinalityKeyValue("outcome", "error");
        assertNoForbiddenLowCardinalityKeys();
    }

    private EmailDeliveryService emailDeliveryService(JavaMailSender mailSender) {
        EmailPolicyService emailPolicyService = org.mockito.Mockito.mock(EmailPolicyService.class);
        when(emailPolicyService.isStopList(any(), any())).thenReturn(false);
        EmailLogService emailLogService = org.mockito.Mockito.mock(EmailLogService.class);

        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setFrom("noreply@example.com");
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        rateLimitProperties.setLimits(Map.of("email-recovery", new RateLimitProperties.Limit(3, 86400)));

        return new EmailDeliveryService(mailSender, org.mockito.Mockito.mock(UserDomainService.class), emailProperties,
                emailPolicyService,
                org.mockito.Mockito.mock(CurrentUserProvider.class), emailLogService, rateLimitProperties,
                emailTemplates,
                observationRegistry, new BusinessMetrics(new SimpleMeterRegistry()));
    }

    /** Запрещённые ключи: id, email и прочие ПДн не должны жить в low-cardinality тегах. */
    private void assertNoForbiddenLowCardinalityKeys() {
        TestObservationRegistryAssert.assertThat(observationRegistry).hasHandledContextsThatSatisfy(contexts -> {
            for (io.micrometer.observation.Observation.ContextView context : contexts) {
                for (io.micrometer.common.KeyValue keyValue : context.getLowCardinalityKeyValues()) {
                    String key = keyValue.getKey().toLowerCase();
                    assertThat(key).as("запрещённый low-cardinality ключ в наблюдении " + context.getName())
                            .doesNotContain("email",
                                    "userid", "user.id", "token", "password", "recipient");
                    assertThat(key).as("id не должен быть в low-cardinality: " + context.getName())
                            .doesNotMatch(".*\\.id$");
                }
            }
        });
    }

    private static PortalModel portal(Long id) {
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName("Test Portal");
        return portal;
    }

    private static TicketModel ticket(Long id) {
        TicketModel ticket = new TicketModel();
        ticket.setId(id);
        ticket.setTitle("Test Ticket");
        ticket.setBody("Description");
        ticket.setTicketStatus(TicketStatus.OPEN);
        ticket.setTicketPriority(TicketPriority.MEDIUM);
        ticket.setTicketLiveStatus(TicketLiveStatus.ACTIVE);
        return ticket;
    }
}

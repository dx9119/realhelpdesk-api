package com.ukhanov.realhelpdesk.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Бизнес-метрики заявок, вложений и писем.
 *
 * <p>
 * Инкремент идёт сразу после успешного коммита доменной операции:
 * {@code saveTicket}/{@code saveMessage}/{@code saveAttachment} возвращаются уже после коммита своей транзакции, письмо
 * считается после приёма SMTP. Откат или исключение до этой точки не доходит, поэтому счётчики не завышаются; сам
 * инкремент на транзакцию не влияет — MeterRegistry работает в памяти.
 * </p>
 *
 * <p>
 * Теги — только значения конечных enum'ов (priority, access, from, to, status, type), конечный список result (как в
 * {@code auth.attempts}) и конечный список групп content_type. Email, userId, ticketId и имена файлов в теги не
 * попадают — они высококардинальные и содержат ПДн.
 * </p>
 *
 * <p>
 * Вызов метрики не должен ронять успешный бизнес-сценарий: запись пропускается при null-параметрах и
 * отрицательных/неизвестных длительностях.
 * </p>
 *
 * @see TicketsOpenGauge gauge открытых заявок tickets.open
 */
@Component
public class BusinessMetrics {

    private static final String TICKETS_CREATED = "tickets.created";
    private static final String TICKETS_STATUS_TRANSITIONS = "tickets.status.transitions";
    private static final String TICKETS_TIME_TO_FIRST_RESPONSE = "tickets.time.to.first.response";
    private static final String TICKETS_TIME_TO_RESOLUTION = "tickets.time.to.resolution";
    private static final String ATTACHMENTS_UPLOADED = "attachments.uploaded";
    private static final String ATTACHMENTS_SIZE = "attachments.size";
    private static final String MAIL_SENT = "mail.sent";

    private static final String DESCRIPTION_TICKETS_CREATED = "Созданные заявки по приоритету и типу доступа";
    private static final String DESCRIPTION_TRANSITIONS = "Переходы статусов заявок";
    private static final String DESCRIPTION_FIRST_RESPONSE = "Время от создания заявки до первого ответа";
    private static final String DESCRIPTION_RESOLUTION = "Время от создания заявки до закрытия";
    private static final String DESCRIPTION_ATTACHMENTS_UPLOADED = "Загруженные вложения по группе типа содержимого";
    private static final String DESCRIPTION_ATTACHMENTS_SIZE = "Размер загруженных вложений";
    private static final String DESCRIPTION_MAIL_SENT = "Отправленные письма по событию и результату";

    /** SLO-бакеты первого ответа: от минуты до суток — видно, где очередь отвечает быстрее часа. */
    private static final Duration[] FIRST_RESPONSE_SLOS = {Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(8), Duration.ofHours(24)};

    /** SLO-бакеты решения заявки: от часа до недели. */
    private static final Duration[] RESOLUTION_SLOS = {Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24),
            Duration.ofHours(72),
            Duration.ofHours(168)};

    private static final Set<String> CONTENT_TYPE_GROUPS = Set.of("image", "text", "application", "video", "audio");

    private final MeterRegistry meterRegistry;

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * tickets.created: считается после коммита saveTicket; теги priority и access берутся из enum'ов сохранённой
     * заявки.
     */
    public void ticketCreated(TicketPriority priority, TicketAccessStatus accessStatus) {
        if (priority == null || accessStatus == null) {
            return;
        }
        Counter.builder(TICKETS_CREATED)
                .description(DESCRIPTION_TICKETS_CREATED)
                .tag("priority", priority.name())
                .tag("access", accessStatus.name())
                .register(meterRegistry)
                .increment();
    }

    /** tickets.status.transitions: переходы с одинаковым статусом не считаются. */
    public void statusTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null || from == to) {
            return;
        }
        Counter.builder(TICKETS_STATUS_TRANSITIONS)
                .description(DESCRIPTION_TRANSITIONS)
                .tag("from", from.name())
                .tag("to", to.name())
                .register(meterRegistry)
                .increment();
    }

    /** tickets.time.to.first.response: сколько прошло от создания заявки до первого в ней сообщения. */
    public void timeToFirstResponse(Instant createdAt) {
        recordElapsed(TICKETS_TIME_TO_FIRST_RESPONSE, DESCRIPTION_FIRST_RESPONSE, FIRST_RESPONSE_SLOS, createdAt);
    }

    /** tickets.time.to.resolution: сколько прошло от создания до закрытия заявки. */
    public void timeToResolution(Instant createdAt) {
        recordElapsed(TICKETS_TIME_TO_RESOLUTION, DESCRIPTION_RESOLUTION, RESOLUTION_SLOS, createdAt);
    }

    /**
     * attachments.uploaded и attachments.size: группа contentType — часть до «/» из конечного списка (image, text,
     * application, video, audio), всё остальное — other; имя файла в теги не попадает.
     */
    public void attachmentUploaded(String contentType, long sizeBytes) {
        String group = contentTypeGroup(contentType);
        Counter.builder(ATTACHMENTS_UPLOADED)
                .description(DESCRIPTION_ATTACHMENTS_UPLOADED)
                .tag("content_type", group)
                .register(meterRegistry)
                .increment();
        DistributionSummary.builder(ATTACHMENTS_SIZE)
                .description(DESCRIPTION_ATTACHMENTS_SIZE)
                .baseUnit("bytes")
                .tag("content_type", group)
                .register(meterRegistry)
                .record(sizeBytes);
    }

    /**
     * mail.sent: считаются фактические попытки отправки внутри deliver (сборка MIME или SMTP); стоп-лист и лимиты
     * письмо не отправляют и счётчик не трогают.
     */
    public void mailSent(NotificationEvent type, boolean success) {
        if (type == null) {
            return;
        }
        Counter.builder(MAIL_SENT)
                .description(DESCRIPTION_MAIL_SENT)
                .tag("type", type.name())
                .tag("result", success ? AuthMetrics.RESULT_SUCCESS : AuthMetrics.RESULT_FAILURE)
                .register(meterRegistry)
                .increment();
    }

    private void recordElapsed(String name, String description, Duration[] serviceLevelObjectives, Instant createdAt) {
        if (createdAt == null) {
            return;
        }
        Duration elapsed = Duration.between(createdAt, Instant.now());
        if (elapsed.isNegative()) {
            return;
        }
        Timer.builder(name)
                .description(description)
                .serviceLevelObjectives(serviceLevelObjectives)
                .register(meterRegistry)
                .record(elapsed);
    }

    private static String contentTypeGroup(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "other";
        }
        int separator = contentType.indexOf('/');
        String group = separator > 0 ? contentType.substring(0, separator) : contentType;
        group = group.toLowerCase(Locale.ROOT);
        return CONTENT_TYPE_GROUPS.contains(group) ? group : "other";
    }

}

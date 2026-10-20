package com.ukhanov.realhelpdesk.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketAccessStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketPriority;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("Бизнес-метрики (BusinessMetrics)")
class BusinessMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private BusinessMetrics businessMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        businessMetrics = new BusinessMetrics(meterRegistry);
    }

    // ────────────────────────────────────────────────
    // tickets.created
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("ticketCreated → счётчик с тегами priority и access из enum'ов, description заполнен")
    void ticketCreated_validEnums_countsWithTagsAndDescription() {

        // given
        // when
        businessMetrics.ticketCreated(TicketPriority.HIGH, TicketAccessStatus.ALL_USERS);
        businessMetrics.ticketCreated(TicketPriority.HIGH, TicketAccessStatus.ALL_USERS);
        businessMetrics.ticketCreated(TicketPriority.LOW, TicketAccessStatus.CREATOR_AND_PORTAL_USERS);

        Counter high = meterRegistry.find("tickets.created").tags("priority", "HIGH", "access", "ALL_USERS").counter();
        // then
        assertThat(high).isNotNull();
        assertThat(high.count()).isEqualTo(2);
        assertThat(high.getId().getDescription()).isNotBlank();

        Counter low = meterRegistry.find("tickets.created").tags("priority", "LOW", "access", "CREATOR_AND_PORTAL_USERS").counter();
        assertThat(low).isNotNull();
        assertThat(low.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("ticketCreated → null-параметры пропускаются, метрика не создаётся")
    void ticketCreated_nullEnumParams_skipsRecording() {

        // given
        // when
        businessMetrics.ticketCreated(null, TicketAccessStatus.ALL_USERS);
        businessMetrics.ticketCreated(TicketPriority.HIGH, null);

        // then
        assertThat(meterRegistry.find("tickets.created").counter()).isNull();
    }

    // ────────────────────────────────────────────────
    // tickets.status.transitions
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("statusTransition → счётчик с тегами from и to")
    void statusTransition_distinctStatuses_countsFromToTags() {

        // given
        // when
        businessMetrics.statusTransition(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
        businessMetrics.statusTransition(TicketStatus.IN_PROGRESS, TicketStatus.CLOSED);

        // then
        assertThat(meterRegistry.find("tickets.status.transitions").tags("from", "OPEN", "to", "IN_PROGRESS").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.find("tickets.status.transitions").tags("from", "IN_PROGRESS", "to", "CLOSED").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("statusTransition → переход в тот же статус и null не считаются")
    void statusTransition_sameStatusOrNull_skipsRecording() {

        // given
        // when
        businessMetrics.statusTransition(TicketStatus.OPEN, TicketStatus.OPEN);
        businessMetrics.statusTransition(null, TicketStatus.CLOSED);
        businessMetrics.statusTransition(TicketStatus.OPEN, null);

        // then
        assertThat(meterRegistry.find("tickets.status.transitions").counter()).isNull();
    }

    // ────────────────────────────────────────────────
    // tickets.time.to.first.response / tickets.time.to.resolution
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("timeToFirstResponse → записана длительность от createdAt, SLO-бакеты на месте")
    void timeToFirstResponse_validCreatedAt_recordsElapsedWithSloBuckets() {

        // given
        // when
        businessMetrics.timeToFirstResponse(Instant.now().minusSeconds(120));

        Timer timer = meterRegistry.find("tickets.time.to.first.response").timer();
        // then
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isBetween(119.0, 121.0);
        assertThat(timer.getId().getDescription()).isNotBlank();

        // границы бакетов в снапшоте — наносекунды: 1m, 5m и 24h из FIRST_RESPONSE_SLOS
        double[] bucketBoundaries = Arrays.stream(timer.takeSnapshot().histogramCounts()).mapToDouble(CountAtBucket::bucket).toArray();
        assertThat(bucketBoundaries).contains(Duration.ofMinutes(1).toNanos(), Duration.ofMinutes(5).toNanos(),
                Duration.ofHours(24).toNanos());
    }

    @Test
    @DisplayName("timeToResolution → записана длительность от createdAt")
    void timeToResolution_validCreatedAt_recordsElapsedTime() {

        // given
        // when
        businessMetrics.timeToResolution(Instant.now().minusSeconds(3600));

        Timer timer = meterRegistry.find("tickets.time.to.resolution").timer();
        // then
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isBetween(3599.0, 3601.0);
    }

    @Test
    @DisplayName("Длительности с null createdAt и из будущего не записываются — метрика не ломает сценарий")
    void durations_nullOrFutureCreatedAt_skipsRecording() {

        // given
        // when
        businessMetrics.timeToFirstResponse(null);
        businessMetrics.timeToResolution(null);
        businessMetrics.timeToResolution(Instant.now().plusSeconds(60));

        // then
        assertThat(meterRegistry.find("tickets.time.to.first.response").timer()).isNull();
        assertThat(meterRegistry.find("tickets.time.to.resolution").timer()).isNull();
    }

    // ────────────────────────────────────────────────
    // attachments.uploaded / attachments.size
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("attachmentUploaded → счётчик и размер по группе contentType (не по имени файла)")
    void attachmentUploaded_knownContentType_countsGroupAndBytes() {

        // given
        // when
        businessMetrics.attachmentUploaded("image/png", 2048);

        Counter uploaded = meterRegistry.find("attachments.uploaded").tags("content_type", "image").counter();
        // then
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.count()).isEqualTo(1);
        assertThat(uploaded.getId().getDescription()).isNotBlank();

        DistributionSummary summary = meterRegistry.find("attachments.size").tags("content_type", "image").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.totalAmount()).isEqualTo(2048);
        assertThat(summary.getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(summary.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("content_type");
    }

    @Test
    @DisplayName("attachmentUploaded → неизвестная и отсутствующая группа попадают в other")
    void attachmentUploaded_unknownGroup_fallsBackToOther() {

        // given
        // when
        businessMetrics.attachmentUploaded("application/pdf", 100);
        businessMetrics.attachmentUploaded("weird/type", 10);
        businessMetrics.attachmentUploaded(null, 5);

        // then
        assertThat(meterRegistry.find("attachments.uploaded").tags("content_type", "application").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("attachments.uploaded").tags("content_type", "other").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.find("attachments.size").tags("content_type", "other").summary().totalAmount()).isEqualTo(15);
    }

    // ────────────────────────────────────────────────
    // mail.sent
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("mailSent → теги type (enum события) и result (success/failure как в auth.attempts)")
    void mailSent_sentEvents_countsTypeAndResultTags() {

        // given
        // when
        businessMetrics.mailSent(NotificationEvent.NEW_TICKET, true);
        businessMetrics.mailSent(NotificationEvent.NEW_TICKET, true);
        businessMetrics.mailSent(NotificationEvent.RECOVERY_PASSWORD, false);

        Counter success = meterRegistry.find("mail.sent").tags("type", "NEW_TICKET", "result", "success").counter();
        // then
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(2);
        assertThat(success.getId().getDescription()).isNotBlank();

        Counter failure = meterRegistry.find("mail.sent").tags("type", "RECOVERY_PASSWORD", "result", "failure").counter();
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("mailSent → null-событие не создаёт метрику")
    void mailSent_nullType_skipsRecording() {

        // given
        // when
        businessMetrics.mailSent(null, true);

        // then
        assertThat(meterRegistry.find("mail.sent").counter()).isNull();
    }

}

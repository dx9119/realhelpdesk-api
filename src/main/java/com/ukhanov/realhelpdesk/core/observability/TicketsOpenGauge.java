package com.ukhanov.realhelpdesk.core.observability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Gauge открытых заявок {@code tickets.open} по статусам.
 *
 * <p>
 * Значения обновляются расписанием раз в 30 секунд count-запросом на статус, а не по запросу scrape — сам скрейп в БД
 * не ходит. Gauge в Micrometer ссылается на значение слабой ссылкой, поэтому сильную ссылку на {@link AtomicLong}
 * держит этот компонент; JPA-сущности в теги и в значение не попадают.
 * </p>
 *
 * <p>
 * Считаются только незакрытые статусы (OPEN, IN_PROGRESS): закрытые заявки «открытыми» не являются, у заявки всегда
 * один из двух статусов, поэтому сумма series равна числу открытых заявок.
 * </p>
 */
@Component
public class TicketsOpenGauge {

    private static final String METRIC_NAME = "tickets.open";
    private static final String DESCRIPTION = "Открытые заявки по статусам (обновление раз в 30 секунд)";
    private static final long REFRESH_INTERVAL_MS = 30_000;
    private static final List<TicketStatus> OPEN_STATUSES = List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);

    private final Map<TicketStatus, AtomicLong> counts = new EnumMap<>(TicketStatus.class);
    private final TicketRepository ticketRepository;

    public TicketsOpenGauge(TicketRepository ticketRepository, MeterRegistry meterRegistry) {
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository must not be null");
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");

        for (TicketStatus status : OPEN_STATUSES) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder(METRIC_NAME, count, AtomicLong::get)
                    .description(DESCRIPTION)
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
    }

    /**
     * Периодическое обновление: при недоступности БД исключение уходит в обработчик планировщика (лог ERROR, следующий
     * запуск через 30 секунд), значения gauge остаются прежними.
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    void refresh() {
        for (TicketStatus status : OPEN_STATUSES) {
            counts.get(status)
                    .set(ticketRepository.countByTicketStatusAndTicketLiveStatus(status, TicketLiveStatus.ACTIVE));
        }
    }

}

package com.ukhanov.realhelpdesk.core.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.domain.ticket.model.TicketLiveStatus;
import com.ukhanov.realhelpdesk.domain.ticket.model.TicketStatus;
import com.ukhanov.realhelpdesk.domain.ticket.repository.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("Gauge открытых заявок (TicketsOpenGauge)")
class TicketsOpenGaugeTest {

    @Mock
    private TicketRepository ticketRepository;

    private SimpleMeterRegistry meterRegistry;
    private TicketsOpenGauge ticketsOpenGauge;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ticketsOpenGauge = new TicketsOpenGauge(ticketRepository, meterRegistry);
    }

    @Test
    @DisplayName("Конструктор регистрирует tickets.open только для открытых статусов, description заполнен")
    void constructor_validRegistry_registersOpenStatusesOnly() {

        // given
        // when
        Gauge open = meterRegistry.find("tickets.open").tags("status", "OPEN").gauge();
        Gauge inProgress = meterRegistry.find("tickets.open").tags("status", "IN_PROGRESS").gauge();

        // then
        assertThat(open).isNotNull();
        assertThat(open.getId().getDescription()).isNotBlank();
        assertThat(inProgress).isNotNull();
        assertThat(meterRegistry.find("tickets.open").tags("status", "CLOSED").gauge()).isNull();
        assertThat(meterRegistry.find("tickets.open").meters()).hasSize(2);
    }

    @Test
    @DisplayName("refresh → значения из count-запросов, чтение gauge в БД не ходит")
    void refresh_ticketCounts_updatesGaugesWithoutExtraDbCalls() {

        // given
        // when
        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.OPEN, TicketLiveStatus.ACTIVE))
                .thenReturn(3L);
        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.IN_PROGRESS, TicketLiveStatus.ACTIVE))
                .thenReturn(5L);

        ticketsOpenGauge.refresh();

        // then
        assertThat(meterRegistry.find("tickets.open").tags("status", "OPEN").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.find("tickets.open").tags("status", "IN_PROGRESS").gauge().value()).isEqualTo(5.0);

        // scrape читает значение из памяти: новые вызовы репозитория не появляются
        assertThat(meterRegistry.find("tickets.open").tags("status", "OPEN").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.find("tickets.open").tags("status", "IN_PROGRESS").gauge().value()).isEqualTo(5.0);
        verify(ticketRepository, times(2)).countByTicketStatusAndTicketLiveStatus(any(TicketStatus.class),
                any(TicketLiveStatus.class));
    }

    @Test
    @DisplayName("refresh → пустая база обнуляет значения после предыдущего обновления")
    void refresh_emptyDatabase_resetsCountsToZero() {

        // given
        // when
        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.OPEN, TicketLiveStatus.ACTIVE))
                .thenReturn(7L);
        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.IN_PROGRESS, TicketLiveStatus.ACTIVE))
                .thenReturn(2L);
        ticketsOpenGauge.refresh();

        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.OPEN, TicketLiveStatus.ACTIVE))
                .thenReturn(0L);
        when(ticketRepository.countByTicketStatusAndTicketLiveStatus(TicketStatus.IN_PROGRESS, TicketLiveStatus.ACTIVE))
                .thenReturn(0L);

        ticketsOpenGauge.refresh();

        // then
        assertThat(meterRegistry.find("tickets.open").tags("status", "OPEN").gauge().value()).isZero();
        assertThat(meterRegistry.find("tickets.open").tags("status", "IN_PROGRESS").gauge().value()).isZero();
    }

}

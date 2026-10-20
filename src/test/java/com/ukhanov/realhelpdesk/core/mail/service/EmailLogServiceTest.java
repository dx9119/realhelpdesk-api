package com.ukhanov.realhelpdesk.core.mail.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ukhanov.realhelpdesk.core.mail.model.EmailLog;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.repository.EmailLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Журнал исходящих писем: EmailLogService")
class EmailLogServiceTest {

    private EmailLogRepository repository;
    private EmailLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailLogRepository.class);
        service = new EmailLogService(repository);
    }

    @Test
    @DisplayName("add сохраняет запись журнала")
    void add_validLog_delegatesSaveToRepository() {

        // given
        // when
        EmailLog log = new EmailLog("Тема", "noreply@example.com", "user@example.com", NotificationEvent.NEW_TICKET);

        service.add(log);

        // then
        verify(repository).save(log);
    }

    @Test
    @DisplayName("add(null) — ошибка, сохранение не вызывается")
    void add_null_throwsNpe() {

        // given / when / then
        assertThatThrownBy(() -> service.add(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("count считает за окно, заканчивающееся текущим моментом")
    void countEmailsSentToByEventInWindow_durationOneHour_calculatesWindowAroundNow() {

        // given
        // when
        when(repository.countEmailsByToAndEventBetween(any(), any(), any(), any())).thenReturn(2L);

        LocalDateTime before = LocalDateTime.now();

        long count = service.countEmailsSentToByEventInWindow("user@example.com", NotificationEvent.RECOVERY_PASSWORD, Duration.ofHours(1));
        LocalDateTime after = LocalDateTime.now();

        // then
        assertThat(count).isEqualTo(2L);

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).countEmailsByToAndEventBetween(eq("user@example.com"), eq(NotificationEvent.RECOVERY_PASSWORD),
                startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isBetween(before.minusHours(1), after.minusHours(1));
        assertThat(endCaptor.getValue()).isBetween(before, after);
        assertThat(Duration.between(startCaptor.getValue(), endCaptor.getValue())).isEqualTo(Duration.ofHours(1));
    }
}

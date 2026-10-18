package com.ukhanov.realhelpdesk.core.mail.repository;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.ukhanov.realhelpdesk.PostgresIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.model.EmailLog;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Журнал писем в БД: EmailLogRepository")
class EmailLogRepositoryIT extends PostgresIntegrationTest {

    private static final String RECIPIENT = "user@example.com";

    @Autowired
    private EmailLogRepository repository;

    @Test
    @DisplayName("Считаются только письма нужного адресата, события и попавшие в окно")
    void countEmails_filtersByRecipientEventAndWindow() {
        LocalDateTime now = LocalDateTime.now();
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusMinutes(5));
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusMinutes(10));
        log(RECIPIENT, NotificationEvent.NEW_TICKET, now.minusMinutes(5));
        log("other@example.com", NotificationEvent.RECOVERY_PASSWORD, now.minusMinutes(5));
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusDays(3));
        repository.flush();

        long count = repository.countEmailsByToAndEventBetween(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusHours(1), now);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Границы окна включительные — письма на границе считаются")
    void countEmails_windowBoundariesAreInclusive() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusHours(1));
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now);
        repository.flush();

        long count = repository.countEmailsByToAndEventBetween(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusHours(1), now);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Вне окна и по другому событию — ноль")
    void countEmails_returnsZeroWhenNothingMatches() {
        LocalDateTime now = LocalDateTime.now();
        log(RECIPIENT, NotificationEvent.NEW_TICKET, now.minusHours(5));
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusDays(2));
        repository.flush();

        assertThat(repository.countEmailsByToAndEventBetween(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusHours(1), now))
                .isZero();
    }

    private void log(String recipient, NotificationEvent event, LocalDateTime sentAt) {
        EmailLog emailLog = new EmailLog("Тема", "noreply@example.com", recipient, event);
        emailLog.setSentAt(sentAt);
        repository.save(emailLog);
    }
}

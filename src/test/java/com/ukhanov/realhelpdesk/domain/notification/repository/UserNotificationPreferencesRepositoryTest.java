package com.ukhanov.realhelpdesk.domain.notification.repository;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Набор событий оповещений — LAZY @ElementCollection при выключенном OSIV: настройки читаются и вне транзакции (GET
 * /notifications/preferences), поэтому find по userId обязан тянуть события тем же запросом.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("Граф событий настроек оповещений")
class UserNotificationPreferencesRepositoryTest {

    private static final Long USER_ID = 4242L;

    @Autowired
    private UserNotificationPreferencesRepository preferencesRepository;

    @BeforeEach
    void clean() {
        preferencesRepository.deleteAll();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Несколько включённых событий → одна строка настроек, набор доступен без сессии")
    void findByUserId_severalEvents_returnsSingleRowWithEvents() {

        // given
        UserNotificationPreferencesModel saved = preferencesRepository
                .save(new UserNotificationPreferencesModel(USER_ID,
                        Set.of(NotificationEvent.NEW_TICKET, NotificationEvent.NEW_MESSAGE)));

        // when
        Optional<UserNotificationPreferencesModel> loaded = preferencesRepository.findByUserId(USER_ID);

        // then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(saved.getId());
        assertThat(loaded.get().getEnabledEvents())
                .containsExactlyInAnyOrder(NotificationEvent.NEW_TICKET, NotificationEvent.NEW_MESSAGE);
    }
}

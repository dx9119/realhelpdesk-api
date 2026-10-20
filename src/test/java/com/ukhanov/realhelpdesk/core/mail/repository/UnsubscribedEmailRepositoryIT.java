package com.ukhanov.realhelpdesk.core.mail.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.model.UnsubscribedEmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Стоп-лист в БД: UnsubscribedEmailRepository")
class UnsubscribedEmailRepositoryIT extends AbstractIntegrationTest {

    private static final String EMAIL = "user@example.com";

    @Autowired
    private UnsubscribedEmailRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Запись сохраняется и находится по адресу")
    void findByEmail_savedRecord_isPresentWithEvent() {

        // given
        // when
        repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.NEW_TICKET));

        // then
        assertThat(repository.findByEmail(EMAIL)).isPresent();
        assertThat(repository.findByEmail(EMAIL).get().getMuteEvent()).isEqualTo(NotificationEvent.NEW_TICKET);
    }

    @Test
    @DisplayName("Неизвестный адрес — пустой результат")
    void findByEmail_unknownAddress_isEmpty() {

        // given / when / then
        assertThat(repository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    @DisplayName("Удаление по адресу убирает запись")
    void deleteByEmail_existingRecord_isRemoved() {

        // given
        // when
        repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.CHANGE_TICKET));

        repository.deleteByEmail(EMAIL);
        repository.flush();

        // then
        assertThat(repository.findByEmail(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("Запись находится по Long-id из сущности")
    void findById_savedEntity_isFoundById() {

        // given
        // when
        UnsubscribedEmail saved = repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.NEW_MESSAGE));

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("Повторное добавление в стоп-лист сохраняет новое время inStopListAt")
    void update_resavedRecord_changesArePersisted() {

        // given
        // when
        UnsubscribedEmail saved = repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.NEW_TICKET));

        UnsubscribedEmail record = repository.findById(saved.getId()).orElseThrow();
        // PostgreSQL хранит timestamp с точностью до микросекунд — усекаем ожидаемое значение
        Instant refreshedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        record.setMuteEvent(NotificationEvent.CHANGE_TICKET);
        record.setInStopListAt(refreshedAt);
        repository.saveAndFlush(record);
        entityManager.flush();
        entityManager.clear();

        UnsubscribedEmail reloaded = repository.findById(saved.getId()).orElseThrow();
        // then
        assertThat(reloaded.getMuteEvent()).isEqualTo(NotificationEvent.CHANGE_TICKET);
        assertThat(reloaded.getInStopListAt()).isEqualTo(refreshedAt);
    }

    @Test
    @DisplayName("Дубль адреса запрещён уникальным индексом")
    void save_duplicateEmail_throwsIntegrityViolation() {

        // given
        // when
        repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.NEW_TICKET));

        // then
        assertThatThrownBy(() -> repository.saveAndFlush(new UnsubscribedEmail(EMAIL, NotificationEvent.CHANGE_TICKET)))
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }
}

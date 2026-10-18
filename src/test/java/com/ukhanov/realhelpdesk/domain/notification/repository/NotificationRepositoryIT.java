package com.ukhanov.realhelpdesk.domain.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.ukhanov.realhelpdesk.PostgresIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Запросы оповещений (NotificationRepository)")
class NotificationRepositoryIT extends PostgresIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserNotificationPreferencesRepository preferencesRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        notificationRepository.deleteAll();
        preferencesRepository.deleteAll();
    }

    @Test
    @DisplayName("Список по получателю и непрочитанные: фильтр и счётчик")
    void listAndUnread() {
        NotificationModel first = save(USER_ID, NotificationEvent.NEW_TICKET, "Первая");
        NotificationModel second = save(USER_ID, NotificationEvent.NEW_MESSAGE, "Вторая");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "Чужая");

        List<NotificationModel> all = notificationRepository.findByRecipientId(USER_ID, PageRequest.of(0, 10)).getContent();
        assertThat(all).extracting(NotificationModel::getId).containsExactlyInAnyOrder(first.getId(), second.getId());

        assertThat(notificationRepository.countByRecipientIdAndReadFalse(USER_ID)).isEqualTo(2);

        first.setRead(true);
        notificationRepository.save(first);
        assertThat(notificationRepository.findByRecipientIdAndReadFalse(USER_ID, PageRequest.of(0, 10)).getContent())
                .extracting(NotificationModel::getId).containsExactly(second.getId());
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("Курсор long polling: только новее afterId, по возрастанию id")
    void cursorAfterId() {
        NotificationModel first = save(USER_ID, NotificationEvent.NEW_TICKET, "1");
        NotificationModel second = save(USER_ID, NotificationEvent.NEW_TICKET, "2");
        NotificationModel third = save(USER_ID, NotificationEvent.NEW_TICKET, "3");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        List<NotificationModel> afterFirst = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(USER_ID, first.getId(),
                PageRequest.of(0, 10));
        assertThat(afterFirst).extracting(NotificationModel::getId).containsExactly(second.getId(), third.getId());

        List<NotificationModel> limited = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(USER_ID, first.getId(),
                PageRequest.of(0, 1));
        assertThat(limited).hasSize(1);
        assertThat(limited.get(0).getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("markAllRead: обновляет только непрочитанные и только своего пользователя")
    void markAllRead() {
        NotificationModel mine = save(USER_ID, NotificationEvent.NEW_TICKET, "1");
        NotificationModel mineRead = save(USER_ID, NotificationEvent.NEW_TICKET, "2");
        mineRead.setRead(true);
        notificationRepository.save(mineRead);
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        int updated = notificationRepository.markAllReadByRecipientId(USER_ID);
        em.clear();

        assertThat(updated).isEqualTo(1);
        Optional<NotificationModel> reloaded = notificationRepository.findById(mine.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isRead()).isTrue();
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(OTHER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("markRead по id и получателю: чужое не находится")
    void findByIdAndRecipientId() {
        NotificationModel mine = save(USER_ID, NotificationEvent.NEW_TICKET, "1");

        assertThat(notificationRepository.findByIdAndRecipientId(mine.getId(), USER_ID)).isPresent();
        assertThat(notificationRepository.findByIdAndRecipientId(mine.getId(), OTHER_USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("Настройки: одна строка на пользователя, уникальность user_id")
    void preferencesByUserId() {
        preferencesRepository.save(new UserNotificationPreferencesModel(USER_ID, Set.of(NotificationEvent.NEW_TICKET)));

        Optional<UserNotificationPreferencesModel> found = preferencesRepository.findByUserId(USER_ID);
        assertThat(found).isPresent();
        assertThat(found.get().getEnabledEvents()).containsExactly(NotificationEvent.NEW_TICKET);
        assertThat(preferencesRepository.findByUserId(OTHER_USER_ID)).isEmpty();
        assertThat(preferencesRepository.findByUserIdIn(Set.of(USER_ID, OTHER_USER_ID))).hasSize(1);
    }

    @Test
    @DisplayName("Кандидаты на повтор: последние непрочитанные строки повторяемых событий; копия теснит оригинал")
    void findRepeatCandidates() {
        NotificationModel original = save(USER_ID, NotificationEvent.NEW_TICKET, "оригинал");
        NotificationModel reminder = reminderOf(USER_ID, original, "напоминание");
        NotificationModel read = save(USER_ID, NotificationEvent.NEW_MESSAGE, "прочитанное");
        read.setRead(true);
        notificationRepository.save(read);
        save(USER_ID, NotificationEvent.CHANGE_TICKET, "не повторяется");
        NotificationModel otherUser = save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужой");

        List<NotificationModel> candidates = notificationRepository
                .findRepeatCandidates(Set.of(NotificationEvent.NEW_TICKET, NotificationEvent.NEW_MESSAGE), PageRequest.of(0, 10));

        // оригинал теснится более новой копией той же группы, прочитанное и неповторяемое событие не кандидаты
        assertThat(candidates).extracting(NotificationModel::getId).containsExactlyInAnyOrder(reminder.getId(), otherUser.getId());
    }

    @Test
    @DisplayName("markGroupRead: гасит оригинал и напоминания группы, чужие и другие группы не трогает")
    void markGroupRead() {
        NotificationModel original = save(USER_ID, NotificationEvent.NEW_TICKET, "оригинал");
        NotificationModel reminder = reminderOf(USER_ID, original, "напоминание");
        NotificationModel sameEventOtherGroup = save(USER_ID, NotificationEvent.NEW_MESSAGE, "другое сообщение");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        int updated = notificationRepository.markGroupReadByRecipientId(USER_ID, original.getId());
        em.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.findById(original.getId())).hasValueSatisfying(row -> assertThat(row.isRead()).isTrue());
        assertThat(notificationRepository.findById(reminder.getId())).hasValueSatisfying(row -> assertThat(row.isRead()).isTrue());
        assertThat(notificationRepository.findById(sameEventOtherGroup.getId()))
                .hasValueSatisfying(row -> assertThat(row.isRead()).isFalse());
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(OTHER_USER_ID)).isEqualTo(1);
    }

    private NotificationModel reminderOf(Long recipientId, NotificationModel source, String title) {
        return notificationRepository.save(new NotificationModel(recipientId, source.getEvent(), 11L, 2L, title, source.getId()));
    }

    private NotificationModel save(Long recipientId, NotificationEvent event, String title) {
        return notificationRepository.save(new NotificationModel(recipientId, event, 11L, 2L, title));
    }
}

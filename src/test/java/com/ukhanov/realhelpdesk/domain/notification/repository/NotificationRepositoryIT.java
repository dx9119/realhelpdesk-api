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

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationCreateCommand;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Запросы оповещений (NotificationRepository)")
class NotificationRepositoryIT extends AbstractIntegrationTest {

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
    void findByRecipientId_twoOwnRows_returnsOwnOnlyAndCountsUnread() {
        // given
        NotificationModel first = save(USER_ID, NotificationEvent.NEW_TICKET, "Первая");
        NotificationModel second = save(USER_ID, NotificationEvent.NEW_MESSAGE, "Вторая");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "Чужая");

        // when
        List<NotificationModel> all = notificationRepository.findByRecipientId(USER_ID, PageRequest.of(0, 10))
                .getContent();
        long unreadBefore = notificationRepository.countByRecipientIdAndReadFalse(USER_ID);

        first.setRead(true);
        notificationRepository.save(first);
        List<NotificationModel> unread = notificationRepository
                .findByRecipientIdAndReadFalse(USER_ID, PageRequest.of(0, 10))
                .getContent();
        long unreadAfter = notificationRepository.countByRecipientIdAndReadFalse(USER_ID);

        // then
        assertThat(all).extracting(NotificationModel::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(unreadBefore).isEqualTo(2);
        assertThat(unread).extracting(NotificationModel::getId).containsExactly(second.getId());
        assertThat(unreadAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("Курсор long polling: только новее afterId, по возрастанию id")
    void findAfterId_cursorGiven_returnsNewerRowsInIdOrder() {
        // given
        NotificationModel first = save(USER_ID, NotificationEvent.NEW_TICKET, "1");
        NotificationModel second = save(USER_ID, NotificationEvent.NEW_TICKET, "2");
        NotificationModel third = save(USER_ID, NotificationEvent.NEW_TICKET, "3");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        // when
        List<NotificationModel> afterFirst = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(
                USER_ID, first.getId(),
                PageRequest.of(0, 10));
        List<NotificationModel> limited = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(USER_ID,
                first.getId(),
                PageRequest.of(0, 1));

        // then
        assertThat(afterFirst).extracting(NotificationModel::getId).containsExactly(second.getId(), third.getId());
        assertThat(limited).hasSize(1);
        assertThat(limited.get(0).getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("markAllRead: обновляет только непрочитанные и только своего пользователя")
    void markAllRead_mixedRows_updatesOnlyOwnUnread() {
        // given
        NotificationModel mine = save(USER_ID, NotificationEvent.NEW_TICKET, "1");
        NotificationModel mineRead = save(USER_ID, NotificationEvent.NEW_TICKET, "2");
        mineRead.setRead(true);
        notificationRepository.save(mineRead);
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        // when
        int updated = notificationRepository.markAllReadByRecipientId(USER_ID);
        em.clear();

        // then
        assertThat(updated).isEqualTo(1);
        Optional<NotificationModel> reloaded = notificationRepository.findById(mine.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isRead()).isTrue();
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(OTHER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("markRead по id и получателю: чужое не находится")
    void findByIdAndRecipientId_foreignRecipient_returnsEmpty() {
        // given
        NotificationModel mine = save(USER_ID, NotificationEvent.NEW_TICKET, "1");

        // when
        Optional<NotificationModel> own = notificationRepository.findByIdAndRecipientId(mine.getId(), USER_ID);
        Optional<NotificationModel> foreign = notificationRepository.findByIdAndRecipientId(mine.getId(),
                OTHER_USER_ID);

        // then
        assertThat(own).isPresent();
        assertThat(foreign).isEmpty();
    }

    @Test
    @DisplayName("Настройки: одна строка на пользователя, уникальность user_id")
    void findByUserId_savedPreferences_returnsOwnOnly() {
        // given
        preferencesRepository.save(new UserNotificationPreferencesModel(USER_ID, Set.of(NotificationEvent.NEW_TICKET)));

        // when
        Optional<UserNotificationPreferencesModel> found = preferencesRepository.findByUserId(USER_ID);
        Optional<UserNotificationPreferencesModel> absent = preferencesRepository.findByUserId(OTHER_USER_ID);
        List<UserNotificationPreferencesModel> both = preferencesRepository
                .findByUserIdIn(Set.of(USER_ID, OTHER_USER_ID));

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getEnabledEvents()).containsExactly(NotificationEvent.NEW_TICKET);
        assertThat(absent).isEmpty();
        assertThat(both).hasSize(1);
    }

    @Test
    @DisplayName("Кандидаты на повтор: последние непрочитанные строки повторяемых событий; копия теснит оригинал")
    void findRepeatCandidates_mixedRows_returnsLatestUnreadPerGroup() {
        // given
        NotificationModel original = save(USER_ID, NotificationEvent.NEW_TICKET, "оригинал");
        NotificationModel reminder = reminderOf(USER_ID, original, "напоминание");
        NotificationModel read = save(USER_ID, NotificationEvent.NEW_MESSAGE, "прочитанное");
        read.setRead(true);
        notificationRepository.save(read);
        save(USER_ID, NotificationEvent.CHANGE_TICKET, "не повторяется");
        NotificationModel otherUser = save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужой");

        // when
        List<NotificationModel> candidates = notificationRepository
                .findRepeatCandidates(Set.of(NotificationEvent.NEW_TICKET, NotificationEvent.NEW_MESSAGE),
                        PageRequest.of(0, 10));

        // then — оригинал теснится более новой копией той же группы, прочитанное и неповторяемое событие не кандидаты
        assertThat(candidates).extracting(NotificationModel::getId)
                .containsExactlyInAnyOrder(reminder.getId(), otherUser.getId());
    }

    @Test
    @DisplayName("markGroupRead: гасит оригинал и напоминания группы, чужие и другие группы не трогает")
    void markGroupRead_groupWithReminder_marksWholeGroupRead() {
        // given
        NotificationModel original = save(USER_ID, NotificationEvent.NEW_TICKET, "оригинал");
        NotificationModel reminder = reminderOf(USER_ID, original, "напоминание");
        NotificationModel sameEventOtherGroup = save(USER_ID, NotificationEvent.NEW_MESSAGE, "другое сообщение");
        save(OTHER_USER_ID, NotificationEvent.NEW_TICKET, "чужая");

        // when
        int updated = notificationRepository.markGroupReadByRecipientId(USER_ID, original.getId());
        em.clear();

        // then
        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.findById(original.getId()))
                .hasValueSatisfying(row -> assertThat(row.isRead()).isTrue());
        assertThat(notificationRepository.findById(reminder.getId()))
                .hasValueSatisfying(row -> assertThat(row.isRead()).isTrue());
        assertThat(notificationRepository.findById(sameEventOtherGroup.getId()))
                .hasValueSatisfying(row -> assertThat(row.isRead()).isFalse());
        assertThat(notificationRepository.countByRecipientIdAndReadFalse(OTHER_USER_ID)).isEqualTo(1);
    }

    private NotificationModel reminderOf(Long recipientId, NotificationModel source, String title) {
        return notificationRepository
                .save(NotificationModel.create(new NotificationCreateCommand(recipientId, source.getEvent(), 11L, 2L,
                        title, source.getId())));
    }

    private NotificationModel save(Long recipientId, NotificationEvent event, String title) {
        return notificationRepository.save(new NotificationModel(recipientId, event, 11L, 2L, title));
    }
}

package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ukhanov.realhelpdesk.core.filter.LoggingFilter;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.model.UserNotificationPreferencesModel;
import com.ukhanov.realhelpdesk.domain.notification.repository.NotificationRepository;
import com.ukhanov.realhelpdesk.domain.notification.repository.UserNotificationPreferencesRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Повторы непрочитанных оповещений о новых заявках и сообщениях ({@link NotificationPublisher#REPEATABLE_EVENTS}): каждые
 * {@link UserNotificationPreferencesModel#getRepeatIntervalMinutes()} минут получателю создаётся новая строка-напоминание с той же группой
 * и уходит письмо-напоминание — пока он не прочитает ни одну из строк группы. Повторяемость и интервал — настройки пользователя;
 * выключенное событие не повторяется ни in-app, ни по email.
 *
 * <p>
 * Обход раз в минуту: интервал задан в минутах, поэтому фактическая периодичность повтора — интервал плюс до одного периода обхода.
 * Пробуждение ожидающих long polling и письма — после коммита: SMTP-обмен не должен держать открытым соединение с БД, а письмо о
 * напоминании не должно уйти при откате транзакции.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class NotificationRepeatService {

    /** За один обход не больше этого напоминаний — остальные догонят следующие запуски. */
    private static final int SCAN_BATCH_SIZE = 500;

    private static final long SCAN_PERIOD_MS = 60_000L;

    /** Технический идентификатор задачи: у фонового запуска нет запроса, к которому его привязать (§5 п. 6). */
    private static final String SCHEDULER_REQUEST_ID = "scheduler-repeat-notifications";
    private static final String SCHEDULER_URI = "scheduled:repeat-notifications";

    private final NotificationRepository notificationRepository;
    private final UserNotificationPreferencesRepository preferencesRepository;
    private final NotificationPublisher notificationPublisher;
    private final UserDomainService userDomainService;
    private final EmailDeliveryService emailDeliveryService;
    private final EmailTemplates emailTemplates;

    @Scheduled(fixedDelay = SCAN_PERIOD_MS, initialDelay = SCAN_PERIOD_MS)
    @Transactional
    public void repeatDueNotifications() {
        // MDC — thread-local и в фоновом потоке пуст: задаём технический контекст, иначе строки
        // планировщика уходят с пустыми колонками.
        MDC.put(LoggingFilter.REQUEST_ID, SCHEDULER_REQUEST_ID);
        MDC.put(LoggingFilter.URI_REQUEST, SCHEDULER_URI);
        try {
            scanAndRepeat();
        } finally {
            MDC.clear();
        }
    }

    private void scanAndRepeat() {
        List<NotificationModel> candidates = notificationRepository.findRepeatCandidates(NotificationPublisher.REPEATABLE_EVENTS,
                PageRequest.of(0, SCAN_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")));
        if (candidates.isEmpty()) {
            return;
        }

        Set<Long> userIds = candidates.stream().map(NotificationModel::getRecipientId).collect(Collectors.toSet());
        Map<Long, UserNotificationPreferencesModel> preferencesByUser = preferencesRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(UserNotificationPreferencesModel::getUserId, preferences -> preferences, (left, right) -> left));

        Instant now = Instant.now();
        List<Long> woken = new ArrayList<>();
        List<NotificationModel> repeated = new ArrayList<>();
        for (NotificationModel source : candidates) {
            if (!repeatAllowed(source, preferencesByUser.get(source.getRecipientId()), now)) {
                continue;
            }
            remind(source);
            woken.add(source.getRecipientId());
            repeated.add(source);
        }

        if (woken.isEmpty()) {
            return;
        }
        notificationPublisher.wakeAfterCommit(woken);
        emailRemindersAfterCommit(repeated);
        // INFO, а не DEBUG: в prod должно быть видно, что механика напоминаний работает (B6)
        logger.info("Созданы повторные оповещения: получателей={}", woken.size());
    }

    /** Нет строки настроек — повтор включён с интервалом по умолчанию; выключенное событие не повторяется. */
    private boolean repeatAllowed(NotificationModel source, UserNotificationPreferencesModel preferences, Instant now) {
        if (preferences == null) {
            return due(source, now, UserNotificationPreferencesModel.DEFAULT_REPEAT_INTERVAL_MINUTES);
        }
        if (!preferences.isRepeatEnabled() || !preferences.getEnabledEvents().contains(source.getEvent())) {
            return false;
        }
        return due(source, now, preferences.getRepeatIntervalMinutes());
    }

    private boolean due(NotificationModel source, Instant now, int intervalMinutes) {
        return !source.getCreatedAt().isAfter(now.minus(Duration.ofMinutes(intervalMinutes)));
    }

    /** Создаёт строку-напоминание в группе источника — получатель тот же, что у in-app канала. */
    private void remind(NotificationModel source) {
        Long groupId = source.getGroupId() != null ? source.getGroupId() : source.getId();
        notificationRepository.save(new NotificationModel(source.getRecipientId(), source.getEvent(), source.getTicketId(),
                source.getPortalId(), source.getTitle(), groupId));
    }

    /**
     * Письма-напоминания — после коммита, как пробуждение long polling: письмо не должно уйти, если транзакция откатится, и
     * SMTP-рукопожатие не должно занимать соединение с БД. Отписка от события учитывается внутри {@link EmailDeliveryService} (стоп-лист),
     * поэтому повтор по email не приходит тем, кто от него отказался.
     */
    private void emailRemindersAfterCommit(List<NotificationModel> sources) {
        List<NotificationModel> emailable = sources.stream().filter(source -> source.getTicketId() != null && source.getPortalId() != null)
                .toList();
        if (emailable.isEmpty()) {
            return;
        }
        // Пользователи читаются ДО регистрации колбэка: вне транзакции сессия закрыта и ленивые поля недоступны
        Map<Long, UserModel> recipients = userDomainService
                .getUsersByIds(emailable.stream().map(NotificationModel::getRecipientId).collect(Collectors.toSet()));
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        runAfterCommit(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                sendEmailReminders(emailable, recipients);
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        });
    }

    private void sendEmailReminders(List<NotificationModel> sources, Map<Long, UserModel> recipients) {
        int sent = 0;
        for (NotificationModel source : sources) {
            UserModel recipient = recipients.get(source.getRecipientId());
            // Удалённый получатель — штатный случай (оповещения переживают пользователя): пропускаем, а не роняем обход
            if (recipient == null) {
                continue;
            }
            if (sendQuietly(recipient, source)) {
                sent++;
            }
        }
        if (sent > 0) {
            logger.info("Отправлены письма-напоминания о непрочитанных оповещениях: {}", sent);
        }
    }

    /**
     * Одно письмо не должно обрывать рассылку остальным и валить фоновую задачу (в отличие от HTTP-контура, где ошибка SMTP — {@code 503}
     * клиенту). Стоп-лист и квоты проверяет {@link EmailDeliveryService}.
     */
    private boolean sendQuietly(UserModel recipient, NotificationModel source) {
        try {
            emailDeliveryService.sendUserNotification(recipient.getEmail(), emailTemplates.notificationRepeatSubject(source.getTitle()),
                    emailTemplates.notificationRepeatBody(source.getTitle(), source.getTicketId(), source.getPortalId()), source.getEvent(),
                    recipient.getId());
            return true;
        } catch (Exception e) {
            logger.warn("Не удалось отправить письмо-напоминание, userId={}, notification={}: {}", recipient.getId(), source.getId(),
                    e.getMessage());
            return false;
        }
    }

    /** Тот же контракт, что у {@link NotificationPublisher#wakeAfterCommit}: вне транзакции действие выполняется сразу. */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

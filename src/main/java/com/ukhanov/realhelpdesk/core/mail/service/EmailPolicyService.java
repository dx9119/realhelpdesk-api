package com.ukhanov.realhelpdesk.core.mail.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.mail.dto.EmailInfoResponse;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.model.UnsubscribedEmail;
import com.ukhanov.realhelpdesk.core.mail.repository.UnsubscribedEmailRepository;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class EmailPolicyService {
    private final UnsubscribedEmailRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public boolean isStopList(String email, NotificationEvent sourceEvent) {
        Objects.requireNonNull(email, "Email должен иметь значение");

        NotificationEvent muteEvent = repository.findByEmail(email).map(UnsubscribedEmail::getMuteEvent).orElse(null);
        return isMuted(muteEvent, sourceEvent);
    }

    /**
     * Батч-проверка стоп-листа по адресам (один запрос вместо N при рассылке): карта адрес → событие «тишины»;
     * отсутствующий адрес означает отсутствие ограничений.
     */
    public Map<String, NotificationEvent> muteEventsByEmails(Collection<String> emails) {
        Objects.requireNonNull(emails, "Список email не может быть null");
        if (emails.isEmpty()) {
            return Map.of();
        }

        return repository.findByEmailIn(emails)
                .stream()
                .collect(Collectors.toMap(UnsubscribedEmail::getEmail, UnsubscribedEmail::getMuteEvent,
                        (first, second) -> first));
    }

    /** «Тишина» по адресу: muteEvent — значение из стоп-листа (или null/NONE — ограничений нет). */
    public boolean isMuted(NotificationEvent muteEvent, NotificationEvent sourceEvent) {
        // ограничений нет: письмо отправляется
        if (muteEvent == null || muteEvent == NotificationEvent.NONE) {
            return false;
        }

        // «тишина» по новым заявкам и сообщениям глушит только их,
        // остальные события (в том числе восстановление пароля) продолжают уходить
        if (muteEvent == NotificationEvent.NEW_TICKET_OR_MESSAGE) {
            return sourceEvent == NotificationEvent.NEW_TICKET || sourceEvent == NotificationEvent.NEW_MESSAGE;
        }

        // глушится только выбранное событие
        return muteEvent == sourceEvent;
    }

    public void deleteFromStopList(Long token) {
        Objects.requireNonNull(token, "Token должен иметь значение");

        UserModel user = currentUserProvider.getCurrentUserModel();

        repository.deleteByEmail(user.getEmail());
        // «Почему не пришло письмо» — первый вопрос при разборе, и ответ должен быть в prod-логе
        logger.info("Пользователь {} вернул email-оповещения", user.getId());
    }

    public void addToStopList(NotificationEvent level) {
        Objects.requireNonNull(level, "Level (NotificationEvent) должен иметь значение");

        UserModel user = currentUserProvider.getCurrentUserModel();

        Optional<UnsubscribedEmail> existing = repository.findByEmail(user.getEmail());
        if (existing.isPresent()) {
            UnsubscribedEmail record = existing.get();
            record.setMuteEvent(level);
            record.setInStopListAt(Instant.now());
            repository.save(record);
        } else {
            UnsubscribedEmail newEntry = new UnsubscribedEmail(user.getEmail(), level);
            newEntry.setInStopListAt(Instant.now());
            repository.save(newEntry);
        }

        logger.info("Пользователь {} изменил email-оповещения: событие={}", user.getId(), level);
    }

    public EmailInfoResponse getEmailInfo() {
        UserModel user = currentUserProvider.getCurrentUserModel();
        NotificationEvent level = repository.findByEmail(user.getEmail())
                .map(UnsubscribedEmail::getMuteEvent)
                .orElse(NotificationEvent.NONE);

        return new EmailInfoResponse(level);
    }

}

package com.ukhanov.realhelpdesk.domain.notification.model;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_notification_preferences")
@Getter
@Setter
public class UserNotificationPreferencesModel {

    /** Повтор включён, пока пользователь не выключил его в настройках. */
    public static final boolean DEFAULT_REPEAT_ENABLED = true;

    /** Интервал между повторами по умолчанию, минут. */
    public static final int DEFAULT_REPEAT_INTERVAL_MINUTES = 30;

    /** Допустимый интервал между повторами в API, минут. */
    public static final int MIN_REPEAT_INTERVAL_MINUTES = 1;
    public static final int MAX_REPEAT_INTERVAL_MINUTES = 10080;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // LAZY + @BatchSize: настройки читаются в оповещалках пачкой (⌈N/50⌉ запросов вместо N),
    // а там, где сущность отдаётся наружу без транзакции, события подгружаются EntityGraph'ом
    // в UserNotificationPreferencesRepository.
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @CollectionTable(name = "user_notification_preferences_events", joinColumns = @JoinColumn(name = "preferences_id"))
    @Column(name = "event", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<NotificationEvent> enabledEvents = new HashSet<>();

    @Column(name = "repeat_enabled", nullable = false)
    private boolean repeatEnabled = DEFAULT_REPEAT_ENABLED;

    @Column(name = "repeat_interval_minutes", nullable = false)
    private int repeatIntervalMinutes = DEFAULT_REPEAT_INTERVAL_MINUTES;

    protected UserNotificationPreferencesModel() {
    }

    public UserNotificationPreferencesModel(Long userId, Set<NotificationEvent> enabledEvents) {
        this.userId = userId;
        this.enabledEvents = new HashSet<>(enabledEvents);
    }

    /** Копирует набор: сеттер изоляирует entity от мутаций внешней коллекции, поэтому оставлен ручной. */
    public void setEnabledEvents(Set<NotificationEvent> enabledEvents) {
        this.enabledEvents = new HashSet<>(enabledEvents);
    }
}

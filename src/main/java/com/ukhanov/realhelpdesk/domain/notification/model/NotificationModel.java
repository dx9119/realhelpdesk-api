package com.ukhanov.realhelpdesk.domain.notification.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notifications",
        indexes = {@Index(name = "idx_notifications_recipient_created", columnList = "recipient_id, created_at"),
                @Index(name = "idx_notifications_recipient_read", columnList = "recipient_id, is_read")})
@Getter
@Setter
public class NotificationModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Кому: пользователь, у которого есть доступ к порталу (владелец или доверенный), без автора действия. */
    @Column(name = "recipient_id", nullable = false, updatable = false)
    private Long recipientId;

    @Column(name = "event", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private NotificationEvent event;

    /** Заявка, породившая оповещение; null для событий уровня портала. */
    @Column(name = "ticket_id", updatable = false)
    private Long ticketId;

    /** Портал, к которому относится событие; null, если неизвестен. */
    @Column(name = "portal_id", updatable = false)
    private Long portalId;

    /** Снапшот названия (заявки или портала) на момент события — чтобы показать строку без запроса к заявке. */
    @Column(name = "title", updatable = false)
    private String title;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /**
     * Группа повторов: id первоисточной строки; null — событие не повторяется (или строка — не повторяемое событие).
     */
    @Column(name = "group_id", updatable = false)
    private Long groupId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationModel() {
    }

    public NotificationModel(Long recipientId, NotificationEvent event, Long ticketId, Long portalId, String title) {
        this(new NotificationCreateCommand(recipientId, event, ticketId, portalId, title));
    }

    private NotificationModel(NotificationCreateCommand command) {
        this.recipientId = command.recipientId();
        this.event = command.event();
        this.ticketId = command.ticketId();
        this.portalId = command.portalId();
        this.title = command.title();
        this.groupId = command.groupId();
        this.read = false;
    }

    /** Фабрика для строк-напоминаний: {@code groupId} задаётся только при insert (колонка updatable=false). */
    public static NotificationModel create(NotificationCreateCommand command) {
        return new NotificationModel(command);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

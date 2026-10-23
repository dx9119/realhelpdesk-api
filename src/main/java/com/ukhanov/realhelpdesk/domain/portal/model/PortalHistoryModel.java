package com.ukhanov.realhelpdesk.domain.portal.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "portal_history")
@Getter
@Setter
public class PortalHistoryModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portal_id", nullable = false)
    private Long portalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PortalHistoryEvent event;

    /** Кто совершил действие; null — системное событие (истечение срока запроса). */
    @Column(name = "actor_id")
    private Long actorId;

    /** Второй участник события: кому передают портал, кого добавили/удалили. */
    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(length = 2000)
    private String reason;

    /** Для изменений портала: какое поле менялось (name, description, visibility, users). */
    @Column(name = "field_name", length = 40)
    private String fieldName;

    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void setCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}

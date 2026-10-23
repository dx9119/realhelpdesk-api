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
@Table(name = "portal_transfer_requests")
@Getter
@Setter
public class PortalTransferRequestModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portal_id", nullable = false)
    private Long portalId;

    /** Текущий владелец, инициировавший передачу. */
    @Column(name = "initiator_id", nullable = false)
    private Long initiatorId;

    /** Пользователь, которого предлагают сделать владельцем. */
    @Column(name = "proposed_owner_id", nullable = false)
    private Long proposedOwnerId;

    @Column(name = "reason_initiator", nullable = false, length = 2000)
    private String reasonInitiator;

    /** Причина подтверждения или отклонения — слово нового владельца; пуста, пока запрос активен. */
    @Column(name = "reason_proposed", length = 2000)
    private String reasonProposed;

    @Column(name = "keep_old_owner", nullable = false)
    private boolean keepOldOwner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PortalTransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Срок действия: после него запрос лениво закрывается как EXPIRED при следующем обращении. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @PrePersist
    public void setCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}

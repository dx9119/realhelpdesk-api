package com.ukhanov.realhelpdesk.domain.knowledgedb.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "knowledgebase_articles")
@Getter
@Setter
public class KnowledgeBaseArticleModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private KnowledgeBaseCategoryModel category;

    // null — статья глобальная (видна во всех порталах); ON DELETE SET NULL:
    // удаление портала не должно уносить общую статью
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_id")
    private PortalModel portal;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean isDeleted = false;

    private Instant timeDelete;

    @Version
    private Integer version;

    @PrePersist
    public void setCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}

package com.ukhanov.realhelpdesk.domain.knowledgedb.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "knowledgebase_categories")
@Getter
@Setter
public class KnowledgeBaseCategoryModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Флаг видимости: DELETE скрывает категорию, строка остаётся — на неё по FK
    // ссылаются статьи, включая мягко удалённые
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

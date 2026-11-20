package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;

@Repository
public interface KnowledgeBaseCategoryRepository extends JpaRepository<KnowledgeBaseCategoryModel, Long> {

    // Сравнение через lower(): уникальность в БД обеспечена функциональным индексом
    // по lower(name), поэтому проверка в приложении и ограничение БД совпадают
    @Query("SELECT COUNT(c) > 0 FROM KnowledgeBaseCategoryModel c WHERE lower(c.name) = lower(:name)")
    boolean existsByName(@Param("name") String name);

    @Query("""
                SELECT COUNT(c) > 0 FROM KnowledgeBaseCategoryModel c
                WHERE lower(c.name) = lower(:name) AND c.id <> :excludeId
            """)
    boolean existsByNameExceptId(@Param("name") String name, @Param("excludeId") Long excludeId);
}

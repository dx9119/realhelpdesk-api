package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;

@Repository
public interface KnowledgeBaseCategoryRepository extends JpaRepository<KnowledgeBaseCategoryModel, Long> {

    /** Справочник для UI: только видимые категории, порядок — по id. */
    @Query("SELECT c FROM KnowledgeBaseCategoryModel c WHERE c.isDeleted = false ORDER BY c.id")
    List<KnowledgeBaseCategoryModel> findAllActive();

    /** Категория по id среди видимых: удалённая отдаётся как отсутствующая. */
    Optional<KnowledgeBaseCategoryModel> findByIdAndIsDeletedFalse(Long id);

    // Сравнение через lower(): уникальность в БД обеспечена частичным индексом
    // по lower(name) WHERE is_deleted = false, поэтому проверка в приложении и
    // ограничение БД совпадают
    @Query("""
                SELECT COUNT(c) > 0 FROM KnowledgeBaseCategoryModel c
                WHERE c.isDeleted = false AND lower(c.name) = lower(:name)
            """)
    boolean existsByName(@Param("name") String name);

    @Query("""
                SELECT COUNT(c) > 0 FROM KnowledgeBaseCategoryModel c
                WHERE c.isDeleted = false AND lower(c.name) = lower(:name) AND c.id <> :excludeId
            """)
    boolean existsByNameExceptId(@Param("name") String name, @Param("excludeId") Long excludeId);
}

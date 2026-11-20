package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;

@Repository
public interface KnowledgeBaseArticleRepository
        extends
            JpaRepository<KnowledgeBaseArticleModel, Long>,
            JpaSpecificationExecutor<KnowledgeBaseArticleModel> {

    // Переопределение JpaSpecificationExecutor.findAll: страница статей сразу с
    // category/portal, иначе toResponse читает category.name построчно (N+1)
    @Override
    @EntityGraph(attributePaths = {"category", "portal"})
    Page<KnowledgeBaseArticleModel> findAll(Specification<KnowledgeBaseArticleModel> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "portal"})
    Optional<KnowledgeBaseArticleModel> findByIdAndIsDeletedFalse(Long id);

    /**
     * Дубликат вопроса в области видимости статьи: глобальная область ({@code portalId = null}) или конкретный портал.
     * {@code excludeId} исключает саму изменяемую статью.
     *
     * <p>
     * Сравнение через {@code lower()}: регистронезависимость одинакова для поиска дубликата и для проверки при
     * изменении. {@code :portalId IS NULL} в JPQL на PostgreSQL не даёт нужной семантики, поэтому условие по порталу
     * разведено на два запроса.
     * </p>
     */
    @Query("""
                SELECT COUNT(a) > 0 FROM KnowledgeBaseArticleModel a
                WHERE a.isDeleted = false
                  AND lower(a.question) = lower(:question)
                  AND a.portal IS NULL
                  AND (:excludeId IS NULL OR a.id <> :excludeId)
            """)
    boolean existsGlobalQuestion(@Param("question") String question, @Param("excludeId") Long excludeId);

    @Query("""
                SELECT COUNT(a) > 0 FROM KnowledgeBaseArticleModel a
                WHERE a.isDeleted = false
                  AND lower(a.question) = lower(:question)
                  AND a.portal.id = :portalId
                  AND (:excludeId IS NULL OR a.id <> :excludeId)
            """)
    boolean existsPortalQuestion(@Param("question") String question, @Param("portalId") Long portalId,
            @Param("excludeId") Long excludeId);

    // Удаление категории запрещено, пока на неё ссылается хоть одна статья — включая мягко удалённую:
    // FK category_id не знает про is_deleted, и такой DELETE падал бы 500-й ошибкой целостности
    boolean existsByCategoryId(Long categoryId);
}

package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import java.util.Set;

import jakarta.persistence.criteria.JoinType;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;

/**
 * Фильтры поиска статей базы знаний.
 *
 * <p>
 * Живёт в domain-слое, потому что используется и контроллером, и репозиторием: feature-слой может зависеть от domain,
 * но не наоборот.
 * </p>
 */
public final class KnowledgeBaseArticleSpecification {

    private KnowledgeBaseArticleSpecification() {
    }

    /** Фильтр: только активные (не удалённые) статьи. */
    public static Specification<KnowledgeBaseArticleModel> active() {
        return (root, query, cb) -> cb.equal(root.get("isDeleted"), Boolean.FALSE);
    }

    /** Фильтр: поиск по вопросу и ответу без учёта регистра. */
    public static Specification<KnowledgeBaseArticleModel> search(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        return (root, query, cb) -> {
            String pattern = "%" + text.toLowerCase() + "%";
            return cb.or(cb.like(cb.lower(root.get("question")), pattern),
                    cb.like(cb.lower(root.get("answer")), pattern));
        };
    }

    /** Фильтр: по идентификатору категории. */
    public static Specification<KnowledgeBaseArticleModel> withCategoryId(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /** Фильтр: статьи конкретного портала, не трогая глобальные. */
    public static Specification<KnowledgeBaseArticleModel> withPortalId(Long portalId) {
        if (portalId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("portal").get("id"), portalId);
    }

    /**
     * Фильтр области видимости: глобальные статьи (портал {@code null}) видны всем, привязанные — только доступным
     * порталам. LEFT JOIN нужен, иначе INNER JOIN отсёк бы глобальные строки.
     */
    public static Specification<KnowledgeBaseArticleModel> inScope(Set<Long> accessiblePortalIds) {
        if (accessiblePortalIds == null || accessiblePortalIds.isEmpty()) {
            return (root, query, cb) -> cb.isNull(root.join("portal", JoinType.LEFT).get("id"));
        }
        return (root, query, cb) -> cb.or(cb.isNull(root.join("portal", JoinType.LEFT).get("id")),
                root.get("portal").get("id").in(accessiblePortalIds));
    }

    /** Основной билдер: собирает все фильтры в одну спецификацию. */
    public static Specification<KnowledgeBaseArticleModel> build(KnowledgeBaseSearchCriteria criteria,
            Set<Long> accessiblePortalIds) {

        Specification<KnowledgeBaseArticleModel> spec = Specification.where(active())
                .and(inScope(accessiblePortalIds));

        if (criteria == null) {
            return spec;
        }

        if (StringUtils.hasText(criteria.search())) {
            spec = spec.and(search(criteria.search()));
        }

        if (criteria.categoryId() != null) {
            spec = spec.and(withCategoryId(criteria.categoryId()));
        }

        if (criteria.portalId() != null) {
            spec = spec.and(withPortalId(criteria.portalId()));
        }

        return spec;
    }
}

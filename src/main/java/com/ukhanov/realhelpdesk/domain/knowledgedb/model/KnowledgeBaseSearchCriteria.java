package com.ukhanov.realhelpdesk.domain.knowledgedb.model;

import jakarta.validation.constraints.Size;

/**
 * Фильтры поиска статей базы знаний.
 *
 * <p>
 * Живёт в domain-слое, потому что используется и контроллером, и {@code KnowledgeBaseArticleSpecification}:
 * feature-слой может зависеть от domain, но не наоборот. Пагинация вынесена в {@code PageCriteria}, чтобы не смешивать
 * фильтры и параметры страницы.
 * </p>
 */
public record KnowledgeBaseSearchCriteria(
        @Size(max = 50, message = "Строка поиска не может превышать 50 символов") String search,
        Long categoryId,
        Long portalId) {
}

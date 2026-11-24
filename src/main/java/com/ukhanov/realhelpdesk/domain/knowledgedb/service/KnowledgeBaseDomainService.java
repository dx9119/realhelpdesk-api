package com.ukhanov.realhelpdesk.domain.knowledgedb.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.transaction.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseArticleRepository;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseArticleSpecification;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseCategoryRepository;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeBaseDomainService {

    private final KnowledgeBaseArticleRepository articleRepository;
    private final KnowledgeBaseCategoryRepository categoryRepository;

    /** Дубликат имени категории при создании. */
    public boolean existsCategoryByName(String name) {
        Objects.requireNonNull(name, "name не должен быть null");
        return categoryRepository.existsByName(name);
    }

    /** Дубликат имени категории при переименовании: сама изменяемая строка не считается конфликтом. */
    public boolean existsCategoryByNameExcept(Long categoryId, String name) {
        Objects.requireNonNull(categoryId, "categoryId не должен быть null");
        Objects.requireNonNull(name, "name не должен быть null");
        return categoryRepository.existsByNameExceptId(name, categoryId);
    }

    /** Дубликат вопроса в той же области видимости (тот же портал или глобально) среди неудалённых статей. */
    public boolean existsArticleByQuestion(Long portalId, String question) {
        Objects.requireNonNull(question, "question не должен быть null");
        if (portalId == null) {
            return articleRepository.existsGlobalQuestion(question, null);
        }
        return articleRepository.existsPortalQuestion(question, portalId, null);
    }

    /** Тот же вопрос у другой статьи — исключение при изменении текущей. */
    public boolean existsArticleByQuestionExcept(Long articleId, Long portalId, String question) {
        Objects.requireNonNull(articleId, "articleId не должен быть null");
        Objects.requireNonNull(question, "question не должен быть null");
        if (portalId == null) {
            return articleRepository.existsGlobalQuestion(question, articleId);
        }
        return articleRepository.existsPortalQuestion(question, portalId, articleId);
    }

    @Transactional
    public KnowledgeBaseCategoryModel saveCategory(KnowledgeBaseCategoryModel category) {
        Objects.requireNonNull(category, "category не должен быть null");
        logger.debug("Сохранена категория {}", category.getId());
        return categoryRepository.save(category);
    }

    @Transactional
    public KnowledgeBaseArticleModel saveArticle(KnowledgeBaseArticleModel article) {
        Objects.requireNonNull(article, "article не должен быть null");
        logger.debug("Сохранена статья {}", article.getId());
        return articleRepository.save(article);
    }

    /** Справочник для UI: только видимые категории. */
    public List<KnowledgeBaseCategoryModel> getActiveCategories() {
        return categoryRepository.findAllActive();
    }

    public KnowledgeBaseCategoryModel getCategoryById(Long categoryId) throws KnowledgeBaseException {
        Objects.requireNonNull(categoryId, "categoryId не должен быть null");
        return categoryRepository.findByIdAndIsDeletedFalse(categoryId)
                .orElseThrow(() -> KnowledgeBaseException.notFound("Категория с ID " + categoryId + " не найдена"));
    }

    public KnowledgeBaseArticleModel getArticleById(Long articleId) throws KnowledgeBaseException {
        Objects.requireNonNull(articleId, "articleId не должен быть null");
        return articleRepository.findByIdAndIsDeletedFalse(articleId)
                .orElseThrow(() -> KnowledgeBaseException.notFound("Статья с ID " + articleId + " не найдена"));
    }

    public Page<KnowledgeBaseArticleModel> getArticlesPage(KnowledgeBaseSearchCriteria criteria,
            Set<Long> accessiblePortalIds, Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        Page<KnowledgeBaseArticleModel> articles = articleRepository.findAll(
                KnowledgeBaseArticleSpecification.build(criteria, accessiblePortalIds), pageable);
        logger.debug("Найдено {} статей по запросу '{}'", articles.getTotalElements(),
                criteria == null ? null : criteria.search());
        return articles;
    }

    /** Каскад флагов при удалении портала: его статьи скрываются вместе с ним. */
    @Transactional
    public int softDeleteArticlesByPortalId(Long portalId, Instant timeDelete) {
        Objects.requireNonNull(portalId, "portalId не должен быть null");
        Objects.requireNonNull(timeDelete, "timeDelete не должен быть null");
        int deleted = articleRepository.softDeleteAllByPortalId(portalId, timeDelete);
        logger.info("Мягко удалено {} статей портала {}", deleted, portalId);
        return deleted;
    }
}

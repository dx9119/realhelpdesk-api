package com.ukhanov.realhelpdesk.domain.knowledgedb.service;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseArticleRepository;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseCategoryRepository;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Доменный сервис базы знаний (KnowledgeBaseDomainService)")
class KnowledgeBaseDomainServiceTest {

    @Mock
    private KnowledgeBaseArticleRepository mockArticleRepository;
    @Mock
    private KnowledgeBaseCategoryRepository mockCategoryRepository;

    private KnowledgeBaseDomainService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseDomainService(mockArticleRepository, mockCategoryRepository);
    }

    // ────────────────────────────────────────────────
    // Дубликаты категорий
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("existsCategoryByName → имя занято → true")
    void existsCategoryByName_takenName_returnsTrue() {

        // given
        when(mockCategoryRepository.existsByName("Оплата")).thenReturn(true);

        // when / then
        assertThat(service.existsCategoryByName("Оплата")).isTrue();
    }

    @Test
    @DisplayName("existsCategoryByNameExcept → проверка исключает саму изменяемую строку")
    void existsCategoryByNameExcept_ownId_isExcluded() {

        // given
        when(mockCategoryRepository.existsByNameExceptId("Оплата", 7L)).thenReturn(false);

        // when / then
        assertThat(service.existsCategoryByNameExcept(7L, "Оплата")).isFalse();
        verify(mockCategoryRepository).existsByNameExceptId("Оплата", 7L);
    }

    // ────────────────────────────────────────────────
    // Дубликаты вопросов
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("existsArticleByQuestion → глобальная область → ищет среди статей без портала")
    void existsArticleByQuestion_nullPortal_queriesGlobalScope() {

        // given
        when(mockArticleRepository.existsGlobalQuestion("Вопрос", null)).thenReturn(true);

        // when / then
        assertThat(service.existsArticleByQuestion(null, "Вопрос")).isTrue();
    }

    @Test
    @DisplayName("existsArticleByQuestion → портал задан → ищет в этом портале")
    void existsArticleByQuestion_portalId_queriesPortalScope() {

        // given
        when(mockArticleRepository.existsPortalQuestion("Вопрос", 9L, null)).thenReturn(false);

        // when / then
        assertThat(service.existsArticleByQuestion(9L, "Вопрос")).isFalse();
    }

    @Test
    @DisplayName("existsArticleByQuestionExcept → глобальная область исключает текущую статью")
    void existsArticleByQuestionExcept_nullPortal_excludesArticle() {

        // given
        when(mockArticleRepository.existsGlobalQuestion("Вопрос", 3L)).thenReturn(true);

        // when / then
        assertThat(service.existsArticleByQuestionExcept(3L, null, "Вопрос")).isTrue();
    }

    @Test
    @DisplayName("existsArticleByQuestionExcept → область портала исключает текущую статью")
    void existsArticleByQuestionExcept_portalScope_excludesArticle() {

        // given
        when(mockArticleRepository.existsPortalQuestion("Вопрос", 9L, 3L)).thenReturn(false);

        // when / then
        assertThat(service.existsArticleByQuestionExcept(3L, 9L, "Вопрос")).isFalse();
    }

    // ────────────────────────────────────────────────
    // Чтение и сохранение
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getCategoryById → категория не найдена → KnowledgeBaseException 404")
    void getCategoryById_missing_throwsNotFound() {

        // given
        when(mockCategoryRepository.findById(42L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getCategoryById(42L))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("не найдена");
    }

    @Test
    @DisplayName("getArticleById → мягко удалённая статья не находится")
    void getArticleById_deleted_throwsNotFound() {

        // given — репозиторий ищет только среди неудалённых
        when(mockArticleRepository.findByIdAndIsDeletedFalse(42L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getArticleById(42L)).isInstanceOf(KnowledgeBaseException.class);
    }

    @Test
    @DisplayName("saveCategory → делегирует в репозиторий")
    void saveCategory_validModel_delegatesToRepository() {

        // given
        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setName("Оплата");
        when(mockCategoryRepository.save(category)).thenReturn(category);

        // when / then
        assertThat(service.saveCategory(category)).isSameAs(category);
    }

    @Test
    @DisplayName("saveArticle → делегирует в репозиторий")
    void saveArticle_validModel_delegatesToRepository() {

        // given
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        when(mockArticleRepository.save(article)).thenReturn(article);

        // when / then
        assertThat(service.saveArticle(article)).isSameAs(article);
        verify(mockArticleRepository).save(article);
    }

    @Test
    @DisplayName("hasArticles → в категории есть статья → true")
    void hasArticles_articleExists_returnsTrue() {

        // given
        when(mockArticleRepository.existsByCategoryId(5L)).thenReturn(true);

        // when / then
        assertThat(service.hasArticles(5L)).isTrue();
    }

    @Test
    @DisplayName("getArticlesPage → делегирует в findAll со спецификацией")
    void getArticlesPage_criteriaGiven_delegatesToFindAll() {

        // given
        when(mockArticleRepository.findAll(ArgumentMatchers.<Specification<KnowledgeBaseArticleModel>>any(),
                any(Pageable.class))).thenReturn(Page.empty());

        // when
        var result = service.getArticlesPage(new KnowledgeBaseSearchCriteria("вопрос", 5L, null), Set.of(9L),
                PageRequest.of(0, 10));

        // then
        assertThat(result.getContent()).isEmpty();
    }
}

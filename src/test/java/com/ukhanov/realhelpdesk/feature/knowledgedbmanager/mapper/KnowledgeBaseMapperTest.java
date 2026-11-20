package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.ArticleResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateArticleRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Маппер базы знаний (KnowledgeBaseMapper)")
class KnowledgeBaseMapperTest {

    private final KnowledgeBaseMapper mapper = new KnowledgeBaseMapperImpl();

    @Test
    @DisplayName("fromRequest → вопрос и ответ из DTO, категория и портал — из параметров")
    void fromRequest_validRequest_mapsQuestionAnswerAndResolvedAssociations() {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");
        PortalModel portal = portal(9L);

        // when
        KnowledgeBaseArticleModel article = mapper.fromRequest(
                new CreateArticleRequest("Как оплатить?", "Через кабинет", 5L, 9L), category, portal);

        // then
        assertThat(article.getQuestion()).isEqualTo("Как оплатить?");
        assertThat(article.getAnswer()).isEqualTo("Через кабинет");
        assertThat(article.getCategory()).isSameAs(category);
        assertThat(article.getPortal()).isSameAs(portal);
        assertThat(article.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("fromRequest → глобальная статья: портал null, категория задана")
    void fromRequest_nullPortal_mapsGlobalArticle() {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");

        // when
        KnowledgeBaseArticleModel article = mapper.fromRequest(
                new CreateArticleRequest("Как оплатить?", "Через кабинет", 5L, null), category, null);

        // then
        assertThat(article.getPortal()).isNull();
        assertThat(article.getCategory()).isSameAs(category);
    }

    @Test
    @DisplayName("toResponse → разворачивает category.id/name и portal.id")
    void toResponse_articleWithAssociations_mapsIdsAndNames() {

        // given
        KnowledgeBaseArticleModel article = article(11L, category(5L, "Оплата"), portal(9L));

        // when
        ArticleResponse response = mapper.toResponse(article);

        // then
        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.question()).isEqualTo("Как оплатить?");
        assertThat(response.answer()).isEqualTo("Через кабинет");
        assertThat(response.categoryId()).isEqualTo(5L);
        assertThat(response.categoryName()).isEqualTo("Оплата");
        assertThat(response.portalId()).isEqualTo(9L);
        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("toResponse → глобальная статья: portalId null, категория заполнена")
    void toResponse_globalArticle_mapsNullPortalId() {

        // given
        KnowledgeBaseArticleModel article = article(11L, category(5L, "Оплата"), null);

        // when
        ArticleResponse response = mapper.toResponse(article);

        // then
        assertThat(response.portalId()).isNull();
        assertThat(response.categoryId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("toResponse(CategoryResponse) → id, name, description, version")
    void toResponse_category_mapsAllFields() {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");
        category.setDescription("Вопросы по счетам");

        // when
        CategoryResponse response = mapper.toResponse(category);

        // then
        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.name()).isEqualTo("Оплата");
        assertThat(response.description()).isEqualTo("Вопросы по счетам");
        assertThat(response.version()).isEqualTo(2);
    }

    private KnowledgeBaseCategoryModel category(Long id, String name) {
        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setId(id);
        category.setName(name);
        category.setVersion(2);
        return category;
    }

    private PortalModel portal(Long id) {
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName("Портал " + id);
        return portal;
    }

    private KnowledgeBaseArticleModel article(Long id, KnowledgeBaseCategoryModel category, PortalModel portal) {
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        article.setId(id);
        article.setQuestion("Как оплатить?");
        article.setAnswer("Через кабинет");
        article.setCategory(category);
        article.setPortal(portal);
        article.setCreatedAt(Instant.parse("2026-01-15T10:00:00Z"));
        article.setVersion(3);
        return article;
    }
}

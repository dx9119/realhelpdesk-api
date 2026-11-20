package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверки на реальном PostgreSQL: уникальность имени категории, поиск по спецификации с {@code @EntityGraph}, защита
 * категории от удаления и поведение внешнего ключа при удалении портала.
 *
 * <p>
 * Профиль {@code test} активируется явно: схему создаёт Liquibase из миграций, а Hibernate её только сверяет
 * ({@code ddl-auto=validate}). Так проверяется и сама миграция {@code 007-knowledgebase-article}.
 * </p>
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = "spring.profiles.active=test")
@DisplayName("Репозитории базы знаний (KnowledgeBaseArticleRepository/KnowledgeBaseCategoryRepository)")
class KnowledgeBaseRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private KnowledgeBaseArticleRepository articleRepository;

    @Autowired
    private KnowledgeBaseCategoryRepository categoryRepository;

    @Autowired
    private EntityManager em;

    private KnowledgeBaseCategoryModel billing;
    private PortalModel portal;

    @BeforeEach
    void setUp() {
        articleRepository.deleteAll();
        categoryRepository.deleteAll();

        UserModel owner = new UserModel();
        owner.setEmail("kb-owner@example.com");
        owner.setFirstName("Иван");
        owner.setLastName("Петров");
        em.persist(owner);

        portal = new PortalModel();
        portal.setName("Портал базы знаний");
        portal.setOwner(owner);
        portal.setAllowedUserIds(new HashSet<>());
        em.persist(portal);

        billing = new KnowledgeBaseCategoryModel();
        billing.setName("Оплата");
        billing.setDescription("Вопросы по счетам");
        em.persist(billing);
        em.flush();
    }

    @Test
    @DisplayName("existsByName → имя в другом регистре считается занятым")
    void existsByName_otherCase_returnsTrue() {

        // given / when / then
        assertThat(categoryRepository.existsByName("ОПЛАТА")).isTrue();
        assertThat(categoryRepository.existsByName("Доступ")).isFalse();
    }

    @Test
    @DisplayName("existsByNameExceptId → та же категория своим id не блокируется")
    void existsByNameExceptId_sameId_returnsFalse() {

        // given / when / then
        assertThat(categoryRepository.existsByNameExceptId("Оплата", billing.getId())).isFalse();
        assertThat(categoryRepository.existsByNameExceptId("Оплата", billing.getId() + 100)).isTrue();
    }

    @Test
    @DisplayName("findByIdAndIsDeletedFalse → удалённая статья не находится")
    void findByIdAndIsDeletedFalse_deletedArticle_returnsEmpty() {

        // given
        KnowledgeBaseArticleModel article = article("Вопрос", billing, portal);
        article.setDeleted(true);
        em.flush();

        // when / then
        assertThat(articleRepository.findByIdAndIsDeletedFalse(article.getId())).isEmpty();
    }

    @Test
    @DisplayName("findAll(spec) → страница читается вместе с category и portal без дополнительных запросов")
    void findAll_specPage_loadsCategoryAndPortalInGraph() {

        // given
        article("Как оплатить?", billing, portal);
        article("Где счёт?", billing, portal);
        em.flush();
        em.clear();

        // when
        Page<KnowledgeBaseArticleModel> page = articleRepository.findAll(
                KnowledgeBaseArticleSpecification.build(new KnowledgeBaseSearchCriteria(null, null, null),
                        Set.of(portal.getId())),
                PageRequest.of(0, 10, Sort.by("createdAt").descending()));

        // then
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allSatisfy(loaded -> {
            assertThat(loaded.getCategory().getName()).isEqualTo("Оплата");
            assertThat(loaded.getPortal().getId()).isEqualTo(portal.getId());
        });
    }

    @Test
    @DisplayName("existsByCategoryId → мягко удалённая статья тоже блокирует категорию")
    void existsByCategoryId_onlyDeleted_returnsTrue() {

        // given
        KnowledgeBaseArticleModel article = article("Вопрос", billing, portal);
        article.setDeleted(true);
        em.flush();

        // when / then
        assertThat(articleRepository.existsByCategoryId(billing.getId())).isTrue();
    }

    @Test
    @DisplayName("Уникальное имя категории → второй insert с тем же именем падает")
    void saveCategory_duplicateName_violatesUniqueConstraint() {

        // given
        KnowledgeBaseCategoryModel duplicate = new KnowledgeBaseCategoryModel();
        duplicate.setName("Оплата");

        // when / then
        assertThatThrownBy(() -> {
            categoryRepository.save(duplicate);
            em.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Дубликат вопроса ищется только среди неудалённых статей того же портала")
    void existsByQuestion_portalScope_ignoresDeletedAndOtherPortals() {

        // given
        article("Как оплатить?", billing, portal);
        em.flush();

        // when / then
        assertThat(articleRepository.existsPortalQuestion("как оплатить?", portal.getId(), null)).isTrue();
        assertThat(articleRepository.existsGlobalQuestion("как оплатить?", null)).isFalse();
    }

    @Test
    @DisplayName("Дубликат вопроса при изменении → текущая статья исключается")
    void existsDuplicateQuestion_ownArticle_isExcluded() {

        // given
        KnowledgeBaseArticleModel article = article("Как оплатить?", billing, portal);
        em.flush();

        // when / then
        assertThat(articleRepository.existsPortalQuestion("Как оплатить?", portal.getId(), article.getId()))
                .isFalse();
        assertThat(articleRepository.existsPortalQuestion("Как оплатить?", portal.getId(), article.getId() + 100))
                .isTrue();
    }

    private KnowledgeBaseArticleModel article(String question, KnowledgeBaseCategoryModel category,
            PortalModel targetPortal) {
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        article.setQuestion(question);
        article.setAnswer("Ответ на вопрос");
        article.setCategory(category);
        article.setPortal(targetPortal);
        em.persist(article);
        return article;
    }
}

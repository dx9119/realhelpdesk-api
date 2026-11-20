package com.ukhanov.realhelpdesk.domain.knowledgedb.repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("Спецификации поиска базы знаний (KnowledgeBaseArticleSpecification)")
class KnowledgeBaseArticleSpecificationTest {

    @Autowired
    private EntityManager em;

    private KnowledgeBaseCategoryModel billing;
    private KnowledgeBaseCategoryModel access;

    private PortalModel portalA;
    private PortalModel portalB;

    private KnowledgeBaseArticleModel globalBilling; // глобальная, billing, «Как оплатить?»
    private KnowledgeBaseArticleModel portalABilling; // портал A, billing
    private KnowledgeBaseArticleModel portalAAccess; // портал A, access, ответ про пароль
    private KnowledgeBaseArticleModel portalBBilling; // портал B, billing
    private KnowledgeBaseArticleModel deletedPortalA; // портал A, удалённая

    @BeforeEach
    void seed() {
        UserModel owner = user("owner@example.com");
        portalA = portal("Портал А", owner);
        portalB = portal("Портал Б", owner);

        billing = category("Оплата", "Вопросы по счетам");
        access = category("Доступ", "Вход и пароли");

        globalBilling = article("Как оплатить подписку?", "Через личный кабинет", billing, null);
        portalABilling = article("Как оплатить подписку?", "Через портал", billing, portalA);
        portalAAccess = article("Забыл пароль", "Восстановите через форму входа", access, portalA);
        portalBBilling = article("Как оплатить подписку?", "Через портал Б", billing, portalB);
        deletedPortalA = article("Устаревший вопрос", "Старый ответ", access, portalA);
        deletedPortalA.setDeleted(true);
        em.flush();
    }

    // ────────────────────────────────────────────────
    // search()
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("search → ищет по вопросу без учёта регистра")
    void search_byQuestionDifferentCase_returnsMatches() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.search("КАК ОПЛАТИТЬ"));

        // then
        assertThat(result).containsExactlyInAnyOrder(globalBilling.getId(), portalABilling.getId(),
                portalBBilling.getId());
    }

    @Test
    @DisplayName("search → ищет по тексту ответа")
    void search_byAnswerText_returnsMatch() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.search("кабинет"));

        // then
        assertThat(result).containsExactly(globalBilling.getId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("search → пустая строка → спецификация не создаётся")
    void search_blankOrNull_returnsNull(String text) {

        // given / when
        Specification<KnowledgeBaseArticleModel> specification = KnowledgeBaseArticleSpecification.search(text);

        // then
        assertThat(specification).isNull();
    }

    // ────────────────────────────────────────────────
    // active(), withCategoryId(), withPortalId()
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("active → отсекает мягко удалённые статьи")
    void active_softDeletedExists_returnsOnlyActive() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.active());

        // then
        assertThat(result).doesNotContain(deletedPortalA.getId()).hasSize(4);
    }

    @Test
    @DisplayName("withCategoryId → фильтрует по категории")
    void withCategoryId_accessCategory_returnsAccessArticles() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.withCategoryId(access.getId()));

        // then
        assertThat(result).containsExactlyInAnyOrder(portalAAccess.getId(), deletedPortalA.getId());
    }

    @Test
    @DisplayName("withPortalId → только статьи указанного портала, без глобальных")
    void withPortalId_portalA_returnsOnlyPortalAArticles() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.withPortalId(portalA.getId()));

        // then
        assertThat(result).containsExactlyInAnyOrder(portalABilling.getId(), portalAAccess.getId(),
                deletedPortalA.getId());
    }

    // ────────────────────────────────────────────────
    // inScope()
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("inScope → глобальные статьи плюс статьи доступных порталов")
    void inScope_portalA_returnsGlobalAndPortalA() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.inScope(Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(globalBilling.getId(), portalABilling.getId(),
                portalAAccess.getId(), deletedPortalA.getId());
        assertThat(result).doesNotContain(portalBBilling.getId());
    }

    @Test
    @DisplayName("inScope → пустой набор порталов → только глобальные статьи")
    void inScope_emptyPortals_returnsOnlyGlobal() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.inScope(Set.of()));

        // then
        assertThat(result).containsExactly(globalBilling.getId());
    }

    // ────────────────────────────────────────────────
    // build()
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("build → без фильтров → активные статьи в области видимости")
    void build_noFilters_returnsActiveInScope() {

        // given / when
        Set<Long> result = run(
                KnowledgeBaseArticleSpecification.build(new KnowledgeBaseSearchCriteria(null, null, null),
                        Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(globalBilling.getId(), portalABilling.getId(),
                portalAAccess.getId());
    }

    @Test
    @DisplayName("build → поиск + категория + портал → точное совпадение")
    void build_allFilters_returnsExactMatch() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.build(
                new KnowledgeBaseSearchCriteria("оплатить", billing.getId(), portalA.getId()),
                Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactly(portalABilling.getId());
    }

    @Test
    @DisplayName("build → портал недоступен пользователю → статья не попадает в результат")
    void build_foreignPortalId_stillScopedToAccessible() {

        // given / when — фильтр по порталу B при области видимости только портал A
        Set<Long> result = run(KnowledgeBaseArticleSpecification.build(
                new KnowledgeBaseSearchCriteria(null, null, portalB.getId()), Set.of(portalA.getId())));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("build → null-критерии → только активные статьи области видимости")
    void build_nullCriteria_returnsActiveInScope() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.build(null, Set.of(portalA.getId())));

        // then
        assertThat(result).containsExactlyInAnyOrder(globalBilling.getId(), portalABilling.getId(),
                portalAAccess.getId());
    }

    @Test
    @DisplayName("build → строка из пробелов не применяется как фильтр")
    void build_blankSearch_isIgnored() {

        // given / when
        Set<Long> result = run(KnowledgeBaseArticleSpecification.build(new KnowledgeBaseSearchCriteria("   ", null,
                null), Set.of(portalA.getId())));

        // then
        assertThat(result).hasSize(3);
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private Set<Long> run(Specification<KnowledgeBaseArticleModel> specification) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<KnowledgeBaseArticleModel> query = cb.createQuery(KnowledgeBaseArticleModel.class);
        Root<KnowledgeBaseArticleModel> root = query.from(KnowledgeBaseArticleModel.class);

        Predicate predicate = specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<KnowledgeBaseArticleModel> result = em.createQuery(query).getResultList();
        return result.stream().map(KnowledgeBaseArticleModel::getId).collect(Collectors.toSet());
    }

    private UserModel user(String email) {
        UserModel user = new UserModel();
        user.setEmail(email);
        user.setFirstName("Иван");
        user.setLastName("Петров");
        em.persist(user);
        return user;
    }

    private PortalModel portal(String name, UserModel owner) {
        PortalModel portal = new PortalModel();
        portal.setName(name);
        portal.setOwner(owner);
        em.persist(portal);
        return portal;
    }

    private KnowledgeBaseCategoryModel category(String name, String description) {
        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setName(name);
        category.setDescription(description);
        category.setCreatedAt(Instant.now());
        em.persist(category);
        return category;
    }

    private KnowledgeBaseArticleModel article(String question, String answer, KnowledgeBaseCategoryModel category,
            PortalModel portal) {
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        article.setQuestion(question);
        article.setAnswer(answer);
        article.setCategory(category);
        article.setPortal(portal);
        em.persist(article);
        return article;
    }
}

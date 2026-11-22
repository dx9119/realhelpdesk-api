package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageCriteria;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseArticleRepository;
import com.ukhanov.realhelpdesk.domain.knowledgedb.service.KnowledgeBaseDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.ArticleResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper.KnowledgeBaseMapperImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Сервис статей базы знаний (KnowledgeBaseManageService)")
class KnowledgeBaseManageServiceTest {

    private static final Long USER_ID = 77L;
    private static final Long PORTAL_ID = 9L;
    private static final Long CATEGORY_ID = 5L;
    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10, Sort.by("createdAt").descending());

    @Mock
    private KnowledgeBaseDomainService mockDomainService;
    @Mock
    private KnowledgeBaseArticleRepository mockArticleRepository;
    @Mock
    private PortalDomainService mockPortalDomainService;
    @Mock
    private PortalRepository mockPortalRepository;
    @Mock
    private AccessValidationService mockAccessValidationService;
    @Mock
    private CurrentUserProvider mockCurrentUserProvider;

    private KnowledgeBaseManageService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseManageService(mockDomainService, mockArticleRepository, mockPortalDomainService,
                mockPortalRepository, mockAccessValidationService, mockCurrentUserProvider,
                new KnowledgeBaseMapperImpl(), new PaginationAdapter());

        lenient().when(mockCurrentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    // ────────────────────────────────────────────────
    // Создание
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("createArticle → владелец портала, вопрос свободен → статья создана")
    void createArticle_portalOwner_createsArticle() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal());
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.existsArticleByQuestion(PORTAL_ID, "Как оплатить?")).thenReturn(false);
        when(mockDomainService.saveArticle(any())).thenAnswer(invocation -> {
            KnowledgeBaseArticleModel article = invocation.getArgument(0);
            article.setId(100L);
            article.setVersion(0);
            return article;
        });

        // when
        ArticleResponse response = service.createArticle(
                new CreateArticleRequest("Как оплатить?", "Через кабинет", CATEGORY_ID, PORTAL_ID));

        // then
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.question()).isEqualTo("Как оплатить?");
        assertThat(response.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.portalId()).isEqualTo(PORTAL_ID);
    }

    @Test
    @DisplayName("createArticle → доверенный пользователь портала, вопрос свободен → статья создана")
    void createArticle_trustedPortalUser_createsArticle() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal());
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.existsArticleByQuestion(PORTAL_ID, "Как оплатить?")).thenReturn(false);
        when(mockDomainService.saveArticle(any())).thenAnswer(invocation -> {
            KnowledgeBaseArticleModel article = invocation.getArgument(0);
            article.setId(102L);
            article.setVersion(0);
            return article;
        });

        // when
        ArticleResponse response = service.createArticle(
                new CreateArticleRequest("Как оплатить?", "Через кабинет", CATEGORY_ID, PORTAL_ID));

        // then
        assertThat(response.id()).isEqualTo(102L);
        assertThat(response.portalId()).isEqualTo(PORTAL_ID);
        verify(mockAccessValidationService).hasPortalManageAccess(PORTAL_ID);
        verify(mockAccessValidationService, never()).hasPortalOwner(any());
    }

    @Test
    @DisplayName("createArticle → посторонний (не владелец и не доверенный) → 403, сохранения нет")
    void createArticle_notPortalManager_throwsForbidden() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal());
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.createArticle(
                new CreateArticleRequest("Вопрос", "Ответ", CATEGORY_ID, PORTAL_ID)))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("владелец или доверенный");
        verify(mockDomainService, never()).saveArticle(any());
    }

    @Test
    @DisplayName("createArticle → глобальная статья, пользователь не оператор → 403")
    void createArticle_globalArticleNotOperator_throwsForbidden() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(user(UserRole.ROLE_USER));

        // when / then
        assertThatThrownBy(() -> service.createArticle(
                new CreateArticleRequest("Вопрос", "Ответ", CATEGORY_ID, null)))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("только оператор");
        verify(mockDomainService, never()).saveArticle(any());
    }

    @Test
    @DisplayName("createArticle → глобальная статья оператором → создана")
    void createArticle_globalArticleOperator_createsArticle() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(user(UserRole.ROLE_OPERATOR));
        when(mockDomainService.existsArticleByQuestion(null, "Как оплатить?")).thenReturn(false);
        when(mockDomainService.saveArticle(any())).thenAnswer(invocation -> {
            KnowledgeBaseArticleModel article = invocation.getArgument(0);
            article.setId(101L);
            article.setVersion(0);
            return article;
        });

        // when
        ArticleResponse response = service.createArticle(
                new CreateArticleRequest("Как оплатить?", "Через кабинет", CATEGORY_ID, null));

        // then
        assertThat(response.portalId()).isNull();
    }

    @Test
    @DisplayName("createArticle → вопрос уже есть в портале → 409")
    void createArticle_duplicateQuestion_throwsConflict() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal());
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.existsArticleByQuestion(PORTAL_ID, "Вопрос")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.createArticle(
                new CreateArticleRequest("Вопрос", "Ответ", CATEGORY_ID, PORTAL_ID)))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("уже есть");
        verify(mockDomainService, never()).saveArticle(any());
    }

    @Test
    @DisplayName("createArticle → категория не найдена → 404 до проверки прав")
    void createArticle_missingCategory_throwsNotFound() throws Exception {

        // given
        when(mockDomainService.getCategoryById(CATEGORY_ID))
                .thenThrow(KnowledgeBaseException.notFound("Категория не найдена"));

        // when / then
        assertThatThrownBy(() -> service.createArticle(
                new CreateArticleRequest("Вопрос", "Ответ", CATEGORY_ID, PORTAL_ID)))
                .isInstanceOf(KnowledgeBaseException.class);
        verifyNoInteractions(mockPortalDomainService);
    }

    // ────────────────────────────────────────────────
    // Замена и патч
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("replaceArticle → устаревший If-Match → 412, записи нет")
    void replaceArticle_staleIfMatch_throwsPreconditionFailed() throws Exception {

        // given
        when(mockDomainService.getArticleById(100L)).thenReturn(article(100L, portal()));
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.replaceArticle(100L,
                new UpdateArticleRequest("Новый", "Ответ", CATEGORY_ID, PORTAL_ID), "\"999\""))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("устарели");
        verify(mockDomainService, never()).saveArticle(any());
    }

    @Test
    @DisplayName("replaceArticle → свежий If-Match → поля заменены целиком")
    void replaceArticle_freshIfMatch_replacesFields() throws Exception {

        // given
        KnowledgeBaseArticleModel article = article(100L, portal());
        when(mockDomainService.getArticleById(100L)).thenReturn(article);
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.getCategoryById(CATEGORY_ID)).thenReturn(category());
        when(mockPortalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal());
        when(mockDomainService.existsArticleByQuestionExcept(100L, PORTAL_ID, "Новый вопрос")).thenReturn(false);
        when(mockDomainService.saveArticle(article)).thenReturn(article);

        // when
        ArticleResponse response = service.replaceArticle(100L,
                new UpdateArticleRequest("Новый вопрос", "Новый ответ", CATEGORY_ID, PORTAL_ID), null);

        // then
        assertThat(response.question()).isEqualTo("Новый вопрос");
        assertThat(response.answer()).isEqualTo("Новый ответ");
    }

    @Test
    @DisplayName("patchArticle → пустое тело → 400")
    void patchArticle_emptyBody_throwsBadRequest() {

        // given / when / then
        assertThatThrownBy(() -> service.patchArticle(100L, new PatchArticleRequest(null, null, null, null), null))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("ни одного поля");
    }

    @Test
    @DisplayName("patchArticle → меняется только ответ, вопрос не проверяется на дубликат")
    void patchArticle_answerOnly_skipsQuestionCheck() throws Exception {

        // given
        KnowledgeBaseArticleModel article = article(100L, portal());
        when(mockDomainService.getArticleById(100L)).thenReturn(article);
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.saveArticle(article)).thenReturn(article);

        // when
        ArticleResponse response = service.patchArticle(100L, new PatchArticleRequest(null, "Новый ответ", null, null),
                null);

        // then
        assertThat(response.answer()).isEqualTo("Новый ответ");
        verify(mockDomainService, never()).existsArticleByQuestionExcept(any(), any(), any());
    }

    @Test
    @DisplayName("patchArticle → смена категории резолвит новую категорию по id")
    void patchArticle_newCategory_resolvesCategory() throws Exception {

        // given
        KnowledgeBaseArticleModel article = article(100L, portal());
        KnowledgeBaseCategoryModel otherCategory = category();
        otherCategory.setId(6L);
        when(mockDomainService.getArticleById(100L)).thenReturn(article);
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.getCategoryById(6L)).thenReturn(otherCategory);
        when(mockDomainService.saveArticle(article)).thenReturn(article);

        // when
        ArticleResponse response = service.patchArticle(100L, new PatchArticleRequest(null, null, 6L, null), null);

        // then
        assertThat(response.categoryId()).isEqualTo(6L);
    }

    @Test
    @DisplayName("patchArticle → глобальная статья, не оператор → 403")
    void patchArticle_globalArticleNotOperator_throwsForbidden() throws Exception {

        // given
        when(mockDomainService.getArticleById(100L)).thenReturn(article(100L, null));
        when(mockCurrentUserProvider.getCurrentUserModel()).thenReturn(user(UserRole.ROLE_USER));

        // when / then
        assertThatThrownBy(() -> service.patchArticle(100L, new PatchArticleRequest("Вопрос", null, null, null), null))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("только оператор");
    }

    // ────────────────────────────────────────────────
    // Удаление
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("deleteArticle → владелец портала → мягкое удаление")
    void deleteArticle_portalOwner_softDeletes() throws Exception {

        // given
        KnowledgeBaseArticleModel article = article(100L, portal());
        when(mockDomainService.getArticleById(100L)).thenReturn(article);
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(true);

        // when
        service.deleteArticle(100L);

        // then
        assertThat(article.isDeleted()).isTrue();
        assertThat(article.getTimeDelete()).isNotNull();
        verify(mockArticleRepository).save(article);
        verify(mockAccessValidationService, never()).hasPortalOwner(any());
    }

    @Test
    @DisplayName("deleteArticle → посторонний (не владелец и не доверенный) → 403, статья не меняется")
    void deleteArticle_notPortalManager_throwsForbidden() throws Exception {

        // given
        KnowledgeBaseArticleModel article = article(100L, portal());
        when(mockDomainService.getArticleById(100L)).thenReturn(article);
        when(mockAccessValidationService.hasPortalManageAccess(PORTAL_ID)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.deleteArticle(100L)).isInstanceOf(KnowledgeBaseException.class);
        assertThat(article.isDeleted()).isFalse();
        verify(mockArticleRepository, never()).save(any());
    }

    // ────────────────────────────────────────────────
    // Поиск
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("searchArticles → без portalId → область видимости из доступных порталов")
    void searchArticles_noPortalFilter_scopesToAccessiblePortals() throws Exception {

        // given
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(portal()));
        when(mockPortalRepository.findAllAccessibleByUserId(USER_ID)).thenReturn(List.of());
        when(mockPortalRepository.findPublicPortalIdsWithUserTickets(USER_ID)).thenReturn(List.of());
        when(mockDomainService.getArticlesPage(any(), eq(Set.of(PORTAL_ID)), eq(DEFAULT_PAGE)))
                .thenReturn(new PageImpl<>(List.of(article(100L, portal())), DEFAULT_PAGE, 1));

        // when
        PageResponse<ArticleResponse> result = service.searchArticles(
                new KnowledgeBaseSearchCriteria(null, null, null), new PageCriteria(0, 10, "createdAt", "desc"));

        // then
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isOne();
    }

    @Test
    @DisplayName("searchArticles → явный portalId без доступа → 403, запроса в БД нет")
    void searchArticles_foreignPortal_throwsForbidden() throws Exception {

        // given
        when(mockAccessValidationService.hasPortalAccess(PORTAL_ID)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.searchArticles(new KnowledgeBaseSearchCriteria(null, null, PORTAL_ID),
                new PageCriteria(0, 10, "createdAt", "desc")))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("недоступен");
        verify(mockDomainService, never()).getArticlesPage(any(), any(), any());
    }

    @Test
    @DisplayName("searchArticles → доступный portalId → область видимости сужается до него")
    void searchArticles_accessiblePortal_narrowsScope() throws Exception {

        // given
        when(mockAccessValidationService.hasPortalAccess(PORTAL_ID)).thenReturn(true);
        when(mockDomainService.getArticlesPage(any(), eq(Set.of(PORTAL_ID)), any()))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        // when
        PageResponse<ArticleResponse> result = service.searchArticles(
                new KnowledgeBaseSearchCriteria("оплата", CATEGORY_ID, PORTAL_ID),
                new PageCriteria(0, 10, "createdAt", "desc"));

        // then
        assertThat(result.content()).isEmpty();
        verify(mockDomainService).getArticlesPage(
                ArgumentMatchers.<KnowledgeBaseSearchCriteria>argThat(c -> c.search().equals("оплата")),
                eq(Set.of(PORTAL_ID)), eq(DEFAULT_PAGE));
    }

    @Test
    @DisplayName("searchArticles → недопустимое поле сортировки → ошибка пагинации")
    void searchArticles_unknownSortField_isRejected() {

        // given / when / then
        assertThatThrownBy(() -> service.searchArticles(new KnowledgeBaseSearchCriteria(null, null, null),
                new PageCriteria(0, 10, "answer", "desc")))
                .isInstanceOf(com.ukhanov.realhelpdesk.core.pagination.exception.PaginationException.class);
    }

    @Test
    @DisplayName("getArticle → пробрасывает маппинг статьи")
    void getArticle_found_mapsToResponse() throws Exception {

        // given
        when(mockDomainService.getArticleById(100L)).thenReturn(article(100L, portal()));
        when(mockAccessValidationService.hasPortalAccess(PORTAL_ID)).thenReturn(true);

        // when
        ArticleResponse response = service.getArticle(100L);

        // then
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.portalId()).isEqualTo(PORTAL_ID);
    }

    @Test
    @DisplayName("getArticle → статья чужого приватного портала → 404, содержимое не раскрывается")
    void getArticle_portalWithoutAccess_throwsNotFound() throws Exception {

        // given
        when(mockDomainService.getArticleById(100L)).thenReturn(article(100L, portal()));
        when(mockAccessValidationService.hasPortalAccess(PORTAL_ID)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.getArticle(100L)).isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("не найдена");
    }

    @Test
    @DisplayName("getArticle → глобальная статья → доступна без проверки портала")
    void getArticle_globalArticle_noPortalCheck() throws Exception {

        // given
        when(mockDomainService.getArticleById(100L)).thenReturn(article(100L, null));

        // when
        ArticleResponse response = service.getArticle(100L);

        // then
        assertThat(response.portalId()).isNull();
        verify(mockAccessValidationService, never()).hasPortalAccess(any());
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private PortalModel portal() {
        PortalModel portal = new PortalModel();
        portal.setId(PORTAL_ID);
        portal.setName("Портал");
        return portal;
    }

    private KnowledgeBaseCategoryModel category() {
        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setId(CATEGORY_ID);
        category.setName("Оплата");
        return category;
    }

    private KnowledgeBaseArticleModel article(Long id, PortalModel portal) {
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        article.setId(id);
        article.setQuestion("Как оплатить?");
        article.setAnswer("Через кабинет");
        article.setCategory(category());
        article.setPortal(portal);
        article.setVersion(0);
        return article;
    }

    private UserModel user(UserRole role) {
        UserModel user = new UserModel();
        user.setId(USER_ID);
        user.setUserRole(role);
        return user;
    }
}

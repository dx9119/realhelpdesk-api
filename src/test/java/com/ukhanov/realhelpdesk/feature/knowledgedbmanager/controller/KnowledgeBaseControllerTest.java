package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.exception.InfrastructureExceptionHandler;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.ArticleResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service.KnowledgeBaseManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты REST-контракта базы знаний (KnowledgeBaseController)")
class KnowledgeBaseControllerTest {

    private static final Long ARTICLE_ID = 100L;
    private static final Long CATEGORY_ID = 5L;
    private static final Long PORTAL_ID = 9L;

    @Mock
    private KnowledgeBaseManageService mockManageService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeBaseController controller = new KnowledgeBaseController(mockManageService);
        mockMvc = MockMvcTester.of(List.of(validatedController(controller)),
                builder -> builder
                        .setControllerAdvice(new GlobalExceptionHandler(), new InfrastructureExceptionHandler())
                        .build());
    }

    /**
     * В приложении @Validated обрабатывает MethodValidationPostProcessor из ValidationAutoConfiguration. В
     * standalone-тесте контекста нет, поэтому прокси создаём тем же пост-процессором вручную.
     */
    private KnowledgeBaseController validatedController(KnowledgeBaseController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        processor.afterPropertiesSet();

        return (KnowledgeBaseController) processor.postProcessAfterInitialization(controller,
                "knowledgeBaseController");
    }

    // ────────────────────────────────────────────────
    // Создание
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /articles → 201 с Location на созданную статью")
    void createArticle_validRequest_returns201WithLocation() throws Exception {

        // given
        when(mockManageService.createArticle(any(CreateArticleRequest.class))).thenReturn(response());

        // when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/articles")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"Как оплатить?","answer":"Через кабинет","categoryId":5,"portalId":9}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).hasHeader("Location", "/api/v1/knowledgebase/articles/100");
        assertThat(result).bodyJson().extractingPath("$.question").asString().isEqualTo("Как оплатить?");
        assertThat(result).bodyJson().extractingPath("$.categoryId").asNumber().isEqualTo(5);
    }

    @Test
    @DisplayName("POST /articles → пустой вопрос → 400")
    void createArticle_blankQuestion_returns400() {

        // given / when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/articles")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"  ","answer":"Через кабинет","categoryId":5}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /articles → нет categoryId → 400")
    void createArticle_missingCategoryId_returns400() {

        // given / when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/articles")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"Вопрос","answer":"Ответ"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────
    // Чтение и поиск
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /articles/{id} → 200 с ETag")
    void getArticle_found_returns200WithEtag() throws Exception {

        // given
        when(mockManageService.getArticle(ARTICLE_ID)).thenReturn(response());

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID).exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader("ETag", "\"kb-article-3\"");
        assertThat(result).bodyJson().extractingPath("$.id").asNumber().isEqualTo(100);
    }

    @Test
    @DisplayName("GET /articles/{id} → статьи нет → 404")
    void getArticle_missing_returns404() throws Exception {

        // given
        when(mockManageService.getArticle(ARTICLE_ID)).thenThrow(KnowledgeBaseException.notFound("Статья не найдена"));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /articles → параметры поиска пробрасываются в сервис")
    void searchArticles_filtersGiven_passesToService() throws Exception {

        // given
        when(mockManageService.searchArticles(any(), any())).thenReturn(emptyPage());

        // when
        MvcTestResult result = mockMvc.get()
                .uri("/api/v1/knowledgebase/articles")
                .param("search", "оплата")
                .param("categoryId", "5")
                .param("portalId", "9")
                .param("page", "1")
                .param("size", "5")
                .exchange();

        // then
        assertThat(result).hasStatusOk();
        verify(mockManageService).searchArticles(
                org.mockito.ArgumentMatchers.argThat(criteria -> "оплата".equals(criteria.search())
                        && CATEGORY_ID.equals(criteria.categoryId())
                        && PORTAL_ID.equals(criteria.portalId())),
                org.mockito.ArgumentMatchers.argThat(page -> page.page() == 1 && page.size() == 5));
    }

    @Test
    @DisplayName("GET /articles?size=0 → 400 (границы пагинации)")
    void searchArticles_zeroSize_returns400() {

        // given / when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/articles").param("size", "0").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────
    // Изменение и удаление
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /articles/{id} → If-Match пробрасывается в сервис, 200 с новым ETag")
    void replaceArticle_ifMatchGiven_passesHeaderAndReturns200() throws Exception {

        // given
        when(mockManageService.replaceArticle(eq(ARTICLE_ID), any(UpdateArticleRequest.class), eq("\"kb-article-3\"")))
                .thenReturn(response());

        // when
        MvcTestResult result = mockMvc.put()
                .uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID)
                .header("If-Match", "\"kb-article-3\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"Как оплатить?","answer":"Через кабинет","categoryId":5,"portalId":9}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader("ETag", "\"kb-article-3\"");
    }

    @Test
    @DisplayName("PUT /articles/{id} → устаревшая версия → 412")
    void replaceArticle_staleVersion_returns412() throws Exception {

        // given
        when(mockManageService.replaceArticle(eq(ARTICLE_ID), any(UpdateArticleRequest.class), any()))
                .thenThrow(KnowledgeBaseException.preconditionFailed("Статья уже изменена"));

        // when
        MvcTestResult result = mockMvc.put()
                .uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID)
                .header("If-Match", "\"kb-article-1\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"Вопрос","answer":"Ответ","categoryId":5}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("PATCH /articles/{id} → 200 с обновлённым ресурсом")
    void patchArticle_validBody_returns200() throws Exception {

        // given
        when(mockManageService.patchArticle(eq(ARTICLE_ID), any(PatchArticleRequest.class), any()))
                .thenReturn(response());

        // when
        MvcTestResult result = mockMvc.patch()
                .uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"answer":"Новый ответ"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").asNumber().isEqualTo(100);
    }

    @Test
    @DisplayName("PATCH /articles/{id} → пустое тело → 400")
    void patchArticle_emptyBody_returns400() throws Exception {

        // given
        when(mockManageService.patchArticle(eq(ARTICLE_ID), any(PatchArticleRequest.class), any()))
                .thenThrow(KnowledgeBaseException.badRequest("Не передано ни одного поля для изменения"));

        // when
        MvcTestResult result = mockMvc.patch()
                .uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DELETE /articles/{id} → 204")
    void deleteArticle_owner_returns204() throws Exception {

        // given / when
        MvcTestResult result = mockMvc.delete().uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        verify(mockManageService).deleteArticle(ARTICLE_ID);
    }

    @Test
    @DisplayName("DELETE /articles/{id} → нет прав → 403")
    void deleteArticle_forbidden_returns403() throws Exception {

        // given
        org.mockito.Mockito.doThrow(KnowledgeBaseException.forbidden("Нет прав"))
                .when(mockManageService)
                .deleteArticle(ARTICLE_ID);

        // when
        MvcTestResult result = mockMvc.delete().uri("/api/v1/knowledgebase/articles/{id}", ARTICLE_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private ArticleResponse response() {
        return new ArticleResponse(ARTICLE_ID, "Как оплатить?", "Через кабинет", CATEGORY_ID, "Оплата", PORTAL_ID,
                Instant.parse("2026-01-15T10:00:00Z"), 3);
    }

    private PageResponse<ArticleResponse> emptyPage() {
        return new PageResponse.Builder<ArticleResponse>().content(List.of())
                .page(1)
                .size(5)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .build();
    }
}

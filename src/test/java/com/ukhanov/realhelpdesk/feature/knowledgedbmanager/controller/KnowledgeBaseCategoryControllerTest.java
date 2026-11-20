package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.exception.InfrastructureExceptionHandler;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service.KnowledgeBaseCategoryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты REST-контракта категорий базы знаний (KnowledgeBaseCategoryController)")
class KnowledgeBaseCategoryControllerTest {

    private static final Long CATEGORY_ID = 5L;

    @Mock
    private KnowledgeBaseCategoryService mockCategoryService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeBaseCategoryController controller = new KnowledgeBaseCategoryController(mockCategoryService);
        mockMvc = MockMvcTester.of(List.of(validatedController(controller)),
                builder -> builder
                        .setControllerAdvice(new GlobalExceptionHandler(), new InfrastructureExceptionHandler())
                        .build());
    }

    /**
     * В приложении @Validated обрабатывает MethodValidationPostProcessor из ValidationAutoConfiguration. В
     * standalone-тесте контекста нет, поэтому прокси создаём тем же пост-процессором вручную.
     */
    private KnowledgeBaseCategoryController validatedController(KnowledgeBaseCategoryController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        processor.afterPropertiesSet();

        return (KnowledgeBaseCategoryController) processor.postProcessAfterInitialization(controller,
                "knowledgeBaseCategoryController");
    }

    @Test
    @DisplayName("GET /categories → 200 с обёрткой ItemsResponse")
    void listCategories_categoriesExist_returnsItemsResponse() throws Exception {

        // given
        when(mockCategoryService.listCategories()).thenReturn(List.of(response()));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/categories").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.total").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.items[0].name").asString().isEqualTo("Оплата");
    }

    @Test
    @DisplayName("GET /categories/{id} → 200 с ETag")
    void getCategory_found_returns200WithEtag() throws Exception {

        // given
        when(mockCategoryService.getCategory(CATEGORY_ID)).thenReturn(response());

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID).exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader("ETag", "\"kb-category-2\"");
    }

    @Test
    @DisplayName("GET /categories/{id} → категории нет → 404")
    void getCategory_missing_returns404() throws Exception {

        // given
        when(mockCategoryService.getCategory(CATEGORY_ID))
                .thenThrow(KnowledgeBaseException.notFound("Категория не найдена"));

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /categories → 201 с Location")
    void createCategory_validRequest_returns201WithLocation() throws Exception {

        // given
        when(mockCategoryService.createCategory(any(CreateCategoryRequest.class))).thenReturn(response());

        // when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Оплата","description":"Вопросы по счетам"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).hasHeader("Location", "/api/v1/knowledgebase/categories/5");
    }

    @Test
    @DisplayName("POST /categories → пустое имя → 400")
    void createCategory_blankName_returns400() {

        // given / when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"   "}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /categories → имя занято → 409")
    void createCategory_duplicateName_returns409() throws Exception {

        // given
        when(mockCategoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenThrow(KnowledgeBaseException.conflict("Категория уже существует"));

        // when
        MvcTestResult result = mockMvc.post()
                .uri("/api/v1/knowledgebase/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Оплата"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("PUT /categories/{id} → 200, If-Match проброшен")
    void replaceCategory_freshVersion_returns200() throws Exception {

        // given
        when(mockCategoryService.replaceCategory(eq(CATEGORY_ID), any(UpdateCategoryRequest.class),
                eq("\"kb-category-2\""))).thenReturn(response());

        // when
        MvcTestResult result = mockMvc.put()
                .uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID)
                .header("If-Match", "\"kb-category-2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Оплата","description":"Новое описание"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatusOk();
    }

    @Test
    @DisplayName("PUT /categories/{id} → устаревшая версия → 412")
    void replaceCategory_staleVersion_returns412() throws Exception {

        // given
        when(mockCategoryService.replaceCategory(eq(CATEGORY_ID), any(UpdateCategoryRequest.class), any()))
                .thenThrow(KnowledgeBaseException.preconditionFailed("Категория уже изменена"));

        // when
        MvcTestResult result = mockMvc.put()
                .uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID)
                .header("If-Match", "\"kb-category-1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Оплата"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("PATCH /categories/{id} → 200 с обновлённым ресурсом")
    void patchCategory_validBody_returns200() throws Exception {

        // given
        when(mockCategoryService.patchCategory(eq(CATEGORY_ID), any(PatchCategoryRequest.class), any()))
                .thenReturn(response());

        // when
        MvcTestResult result = mockMvc.patch()
                .uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description":"Новое описание"}
                        """)
                .exchange();

        // then
        assertThat(result).hasStatusOk();
    }

    @Test
    @DisplayName("DELETE /categories/{id} → 204")
    void deleteCategory_unused_returns204() throws Exception {

        // given / when
        MvcTestResult result = mockMvc.delete().uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        verify(mockCategoryService).deleteCategory(CATEGORY_ID);
    }

    @Test
    @DisplayName("DELETE /categories/{id} → на категорию ссылается статья → 409")
    void deleteCategory_inUse_returns409() throws Exception {

        // given
        org.mockito.Mockito.doThrow(KnowledgeBaseException.conflict("Нельзя удалить категорию"))
                .when(mockCategoryService)
                .deleteCategory(CATEGORY_ID);

        // when
        MvcTestResult result = mockMvc.delete().uri("/api/v1/knowledgebase/categories/{id}", CATEGORY_ID).exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
    }

    private CategoryResponse response() {
        return new CategoryResponse(CATEGORY_ID, "Оплата", "Вопросы по счетам", 2);
    }
}

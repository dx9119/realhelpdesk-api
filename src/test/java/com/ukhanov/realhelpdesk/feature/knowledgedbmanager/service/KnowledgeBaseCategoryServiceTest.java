package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseCategoryRepository;
import com.ukhanov.realhelpdesk.domain.knowledgedb.service.KnowledgeBaseDomainService;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper.KnowledgeBaseMapperImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Сервис категорий базы знаний (KnowledgeBaseCategoryService)")
class KnowledgeBaseCategoryServiceTest {

    @Mock
    private KnowledgeBaseCategoryRepository mockCategoryRepository;
    @Mock
    private KnowledgeBaseDomainService mockDomainService;

    private KnowledgeBaseCategoryService service;

    @BeforeEach
    void setUp() throws KnowledgeBaseException {
        service = new KnowledgeBaseCategoryService(mockCategoryRepository, mockDomainService,
                new KnowledgeBaseMapperImpl());
    }

    @Test
    @DisplayName("createCategory → имя свободно → категория сохраняется")
    void createCategory_freeName_savesCategory() throws KnowledgeBaseException {

        // given
        when(mockDomainService.existsCategoryByName("Оплата")).thenReturn(false);
        when(mockDomainService.saveCategory(any(KnowledgeBaseCategoryModel.class))).thenAnswer(invocation -> {
            KnowledgeBaseCategoryModel category = invocation.getArgument(0);
            category.setId(5L);
            category.setVersion(0);
            return category;
        });

        // when
        CategoryResponse response = service.createCategory(new CreateCategoryRequest("Оплата", "Счета"));

        // then
        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.name()).isEqualTo("Оплата");
        assertThat(response.description()).isEqualTo("Счета");
    }

    @Test
    @DisplayName("createCategory → имя занято → 409, сохранения нет")
    void createCategory_takenName_throwsConflict() throws KnowledgeBaseException {

        // given
        when(mockDomainService.existsCategoryByName("Оплата")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.createCategory(new CreateCategoryRequest("Оплата", null)))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("уже существует");
        verify(mockDomainService, never()).saveCategory(any());
    }

    @Test
    @DisplayName("listCategories → возвращает все категории справочника")
    void listCategories_anyState_returnsAll() throws KnowledgeBaseException {

        // given
        when(mockCategoryRepository.findAll()).thenReturn(List.of(category(1L, "Оплата"), category(2L, "Доступ")));

        // when
        List<CategoryResponse> result = service.listCategories();

        // then
        assertThat(result).extracting(CategoryResponse::name).containsExactly("Оплата", "Доступ");
    }

    @Test
    @DisplayName("replaceCategory → If-Match не совпал → 412 до записи")
    void replaceCategory_staleIfMatch_throwsPreconditionFailed() throws KnowledgeBaseException {

        // given
        when(mockDomainService.getCategoryById(5L)).thenReturn(category(5L, "Оплата"));

        // when / then
        assertThatThrownBy(() -> service.replaceCategory(5L, new UpdateCategoryRequest("Счета", null), "\"2\""))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("устарели");
        verify(mockDomainService, never()).saveCategory(any());
    }

    @Test
    @DisplayName("replaceCategory → If-Match совпал → поля заменены")
    void replaceCategory_freshIfMatch_updatesFields() throws KnowledgeBaseException {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");
        when(mockDomainService.getCategoryById(5L)).thenReturn(category);
        when(mockDomainService.existsCategoryByNameExcept(5L, "Счета")).thenReturn(false);
        when(mockDomainService.saveCategory(category)).thenReturn(category);

        // when
        CategoryResponse response = service.replaceCategory(5L, new UpdateCategoryRequest("Счета", "Про счета"),
                "\"kb-category-0\"");

        // then
        assertThat(response.name()).isEqualTo("Счета");
        assertThat(response.description()).isEqualTo("Про счета");
    }

    @Test
    @DisplayName("replaceCategory → новое имя занято другой категорией → 409")
    void replaceCategory_nameTakenByOther_throwsConflict() throws KnowledgeBaseException {

        // given
        when(mockDomainService.getCategoryById(5L)).thenReturn(category(5L, "Оплата"));
        when(mockDomainService.existsCategoryByNameExcept(5L, "Доступ")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.replaceCategory(5L, new UpdateCategoryRequest("Доступ", null), null))
                .isInstanceOf(KnowledgeBaseException.class);
    }

    @Test
    @DisplayName("patchCategory → пустое тело → 400")
    void patchCategory_emptyBody_throwsBadRequest() throws KnowledgeBaseException {

        // given / when / then
        assertThatThrownBy(() -> service.patchCategory(5L, new PatchCategoryRequest(null, null), null))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("ни одного поля");
    }

    @Test
    @DisplayName("patchCategory → меняется только описание, имя не проверяется на дубликат")
    void patchCategory_descriptionOnly_keepsName() throws KnowledgeBaseException {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");
        when(mockDomainService.getCategoryById(5L)).thenReturn(category);
        when(mockDomainService.saveCategory(category)).thenReturn(category);

        // when
        CategoryResponse response = service.patchCategory(5L, new PatchCategoryRequest(null, "Новое описание"), null);

        // then
        assertThat(response.name()).isEqualTo("Оплата");
        assertThat(response.description()).isEqualTo("Новое описание");
        verify(mockDomainService, never()).existsCategoryByNameExcept(any(), any());
    }

    @Test
    @DisplayName("deleteCategory → на категорию ссылается статья → 409, удаления нет")
    void deleteCategory_categoryInUse_throwsConflict() throws KnowledgeBaseException {

        // given
        when(mockDomainService.getCategoryById(5L)).thenReturn(category(5L, "Оплата"));
        when(mockDomainService.hasArticles(5L)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.deleteCategory(5L)).isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("Нельзя удалить");
        verify(mockCategoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteCategory → свободная категория → удаляется")
    void deleteCategory_unused_deletes() throws KnowledgeBaseException {

        // given
        KnowledgeBaseCategoryModel category = category(5L, "Оплата");
        when(mockDomainService.getCategoryById(5L)).thenReturn(category);
        when(mockDomainService.hasArticles(5L)).thenReturn(false);

        // when
        service.deleteCategory(5L);

        // then
        verify(mockCategoryRepository).delete(category);
    }

    @Test
    @DisplayName("getCategory → найденная категория маппится в DTO")
    void getCategory_found_mapsToDto() throws KnowledgeBaseException {

        // given
        when(mockDomainService.getCategoryById(5L)).thenReturn(category(5L, "Оплата"));

        // when
        CategoryResponse response = service.getCategory(5L);

        // then
        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.name()).isEqualTo("Оплата");
    }

    @Test
    @DisplayName("createCategory → сохранённая категория возвращается с id")
    void createCategory_saved_returnedWithId() throws KnowledgeBaseException {

        // given
        when(mockDomainService.existsCategoryByName("Доступ")).thenReturn(false);
        when(mockDomainService.saveCategory(any())).thenReturn(category(11L, "Доступ"));

        // when
        CategoryResponse response = service.createCategory(new CreateCategoryRequest("Доступ", null));

        // then
        assertThat(response.id()).isEqualTo(11L);
    }

    private KnowledgeBaseCategoryModel category(Long id, String name) {
        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setId(id);
        category.setName(name);
        category.setVersion(0);
        return category;
    }
}

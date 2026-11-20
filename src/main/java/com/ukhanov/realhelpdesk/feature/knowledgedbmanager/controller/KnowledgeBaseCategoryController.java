package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.pagination.dto.ItemsResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service.KnowledgeBaseCategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/knowledgebase/categories")
public class KnowledgeBaseCategoryController {

    private final KnowledgeBaseCategoryService knowledgeBaseCategoryService;

    @Operation(summary = "Справочник категорий базы знаний",
            description = "Список всех категорий для выбора в UI.")
    @GetMapping
    public ResponseEntity<ItemsResponse<CategoryResponse>> listCategories() {
        return ResponseEntity.ok(ItemsResponse.of(knowledgeBaseCategoryService.listCategories()));
    }

    @Operation(summary = "Категория по ID", description = "Возвращает детальную информацию о категории.")
    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable Long categoryId)
            throws KnowledgeBaseException {
        CategoryResponse response = knowledgeBaseCategoryService.getCategory(categoryId);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-category", response.version())).body(response);
    }

    @Operation(summary = "Создание категории базы знаний",
            description = "Создаёт категорию в справочнике. Имя категории уникально регистронезависимо.")
    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) throws KnowledgeBaseException {
        CategoryResponse response = knowledgeBaseCategoryService.createCategory(request);
        return ResponseEntity.created(URI.create("/api/v1/knowledgebase/categories/" + response.id())).body(response);
    }

    @Operation(summary = "Полная замена категории",
            description = "Заменяет название и описание. If-Match с версией из GET "
                    + "защищает от потери чужого изменения.")
    @PutMapping("/{categoryId}")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<CategoryResponse> replaceCategory(@PathVariable Long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch)
            throws KnowledgeBaseException {
        CategoryResponse response = knowledgeBaseCategoryService.replaceCategory(categoryId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-category", response.version())).body(response);
    }

    @Operation(summary = "Частичное обновление категории (PATCH)",
            description = "Меняет только переданные поля (null — не трогать). If-Match с версией из GET обязателен "
                    + "для защиты от потери чужого изменения.")
    @PatchMapping("/{categoryId}")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<CategoryResponse> patchCategory(@PathVariable Long categoryId,
            @Valid @RequestBody PatchCategoryRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch)
            throws KnowledgeBaseException {
        CategoryResponse response = knowledgeBaseCategoryService.patchCategory(categoryId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-category", response.version())).body(response);
    }

    @Operation(summary = "Удаление категории",
            description = "Удаляет категорию из справочника. 409, если на неё ссылается активная статья.")
    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasRole('OPERATOR')")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long categoryId) throws KnowledgeBaseException {
        knowledgeBaseCategoryService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
    }
}

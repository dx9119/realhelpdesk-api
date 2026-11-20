package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.repository.KnowledgeBaseCategoryRepository;
import com.ukhanov.realhelpdesk.domain.knowledgedb.service.KnowledgeBaseDomainService;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateCategoryRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper.KnowledgeBaseMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Справочник категорий базы знаний.
 *
 * <p>
 * Удаление категории запрещено, пока на неё ссылается хотя бы одна неотмёрзшая статья: ссылка в статье по
 * {@code category_id}, «висячий» FK означал бы статью без категории. Имя уникально регистронезависимо, поэтому
 * переименование проверяется с исключением самой изменяемой строки.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class KnowledgeBaseCategoryService {

    private final KnowledgeBaseCategoryRepository categoryRepository;
    private final KnowledgeBaseDomainService knowledgeBaseDomainService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public List<CategoryResponse> listCategories() {
        logger.debug("Запрос списка категорий базы знаний");
        return categoryRepository.findAll().stream().map(knowledgeBaseMapper::toResponse).toList();
    }

    public CategoryResponse getCategory(Long categoryId) throws KnowledgeBaseException {
        return knowledgeBaseMapper.toResponse(knowledgeBaseDomainService.getCategoryById(categoryId));
    }

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) throws KnowledgeBaseException {
        Objects.requireNonNull(request, "CreateCategoryRequest не должен быть null");

        if (knowledgeBaseDomainService.existsCategoryByName(request.name())) {
            throw KnowledgeBaseException.conflict("Категория с названием «" + request.name() + "» уже существует");
        }

        KnowledgeBaseCategoryModel category = new KnowledgeBaseCategoryModel();
        category.setName(request.name());
        category.setDescription(request.description());

        KnowledgeBaseCategoryModel saved = knowledgeBaseDomainService.saveCategory(category);
        logger.info("Создана категория {} («{}»)", saved.getId(), saved.getName());
        return knowledgeBaseMapper.toResponse(saved);
    }

    @Transactional
    public CategoryResponse replaceCategory(Long categoryId, UpdateCategoryRequest request, String ifMatch)
            throws KnowledgeBaseException {
        Objects.requireNonNull(request, "UpdateCategoryRequest не должен быть null");

        KnowledgeBaseCategoryModel category = knowledgeBaseDomainService.getCategoryById(categoryId);
        requireFreshVersion(category, ifMatch);
        requireNameFree(categoryId, request.name());

        category.setName(request.name());
        category.setDescription(request.description());

        KnowledgeBaseCategoryModel saved = knowledgeBaseDomainService.saveCategory(category);
        logger.info("Категория {} заменена: имя — {}", categoryId, saved.getName());
        return knowledgeBaseMapper.toResponse(saved);
    }

    /**
     * Частичное обновление: меняются только переданные поля. Полная замена — {@code PUT}: там клиент обязан прислать
     * ресурс целиком, а здесь достаточно одного поля.
     */
    @Transactional
    public CategoryResponse patchCategory(Long categoryId, PatchCategoryRequest request, String ifMatch)
            throws KnowledgeBaseException {
        Objects.requireNonNull(request, "PatchCategoryRequest не должен быть null");
        if (request.isEmpty()) {
            throw KnowledgeBaseException.badRequest("Не передано ни одного поля для изменения");
        }

        KnowledgeBaseCategoryModel category = knowledgeBaseDomainService.getCategoryById(categoryId);
        requireFreshVersion(category, ifMatch);

        if (request.name() != null && !Objects.equals(category.getName(), request.name())) {
            requireNameFree(categoryId, request.name());
            category.setName(request.name());
        }
        if (request.description() != null && !Objects.equals(category.getDescription(), request.description())) {
            category.setDescription(request.description());
        }

        KnowledgeBaseCategoryModel saved = knowledgeBaseDomainService.saveCategory(category);
        logger.info("Категория {} обновлена частично (PATCH)", categoryId);
        return knowledgeBaseMapper.toResponse(saved);
    }

    @Transactional
    public void deleteCategory(Long categoryId) throws KnowledgeBaseException {
        KnowledgeBaseCategoryModel category = knowledgeBaseDomainService.getCategoryById(categoryId);

        if (knowledgeBaseDomainService.hasArticles(categoryId)) {
            throw KnowledgeBaseException
                    .conflict("Нельзя удалить категорию «" + category.getName() + "»: на неё ссылается статья");
        }

        categoryRepository.delete(category);
        logger.info("Удалена категория {}", categoryId);
    }

    private void requireNameFree(Long categoryId, String name) throws KnowledgeBaseException {
        if (knowledgeBaseDomainService.existsCategoryByNameExcept(categoryId, name)) {
            throw KnowledgeBaseException.conflict("Категория с названием «" + name + "» уже существует");
        }
    }

    /** If-Match: клиент присылает версию, которую видел; несовпадение — 412 до записи, а не 409 после факта. */
    private void requireFreshVersion(KnowledgeBaseCategoryModel category, String ifMatch)
            throws KnowledgeBaseException {
        if (!EntityTag.satisfiedBy(ifMatch, EntityTag.of("kb-category", category.getVersion()),
                category.getVersion())) {
            throw new KnowledgeBaseException(
                    "Категория уже изменена: данные устарели, обновите страницу и повторите",
                    HttpStatus.PRECONDITION_FAILED);
        }
    }
}

package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageCriteria;
import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseSearchCriteria;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.ArticleResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.PatchArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.UpdateArticleRequest;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service.KnowledgeBaseManageService;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/knowledgebase/articles")
public class KnowledgeBaseController {

    private final KnowledgeBaseManageService knowledgeBaseManageService;

    @Operation(summary = "Создание статьи базы знаний",
            description = "Создаёт статью в портале (владелец или доверенный пользователь портала) "
                    + "или глобально (только оператор). Возвращает id и Location на созданный ресурс.")
    @PostMapping
    @PreAuthorize("@knowledgeBaseAccessValidationService.canCreate(#request.portalId())")
    @ApiResponse(responseCode = "201", description = "Создано (заголовок Location — на созданный ресурс)")
    public ResponseEntity<ArticleResponse> createArticle(@Valid @RequestBody CreateArticleRequest request)
            throws KnowledgeBaseException, PortalException {
        ArticleResponse response = knowledgeBaseManageService.createArticle(request);
        return ResponseEntity.created(URI.create("/api/v1/knowledgebase/articles/" + response.id())).body(response);
    }

    @Operation(summary = "Поиск и лента статей базы знаний",
            description = "Фильтрация по строке, категории и порталу. Возвращает глобальные статьи "
                    + "плюс статьи порталов, доступных текущему пользователю. "
                    + "Если передан portalId — поиск только в этом портале.")
    @GetMapping
    @PreAuthorize("@knowledgeBaseAccessValidationService.canSearch(#criteria.portalId())")
    public PageResponse<ArticleResponse> searchArticles(
            @Valid @ParameterObject @ModelAttribute KnowledgeBaseSearchCriteria criteria,
            @Valid @ParameterObject @ModelAttribute PageCriteria pageCriteria)
            throws KnowledgeBaseException, PortalException {

        return knowledgeBaseManageService.searchArticles(criteria, pageCriteria);
    }

    @Operation(summary = "Статья по ID", description = "Возвращает детальную информацию о статье.")
    @GetMapping("/{articleId}")
    @PreAuthorize("@knowledgeBaseAccessValidationService.canRead(#articleId)")
    public ResponseEntity<ArticleResponse> getArticle(@PathVariable Long articleId)
            throws KnowledgeBaseException, PortalException {
        ArticleResponse response = knowledgeBaseManageService.getArticle(articleId);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-article", response.version())).body(response);
    }

    @Operation(summary = "Полная замена статьи",
            description = "Заменяет вопрос, ответ, категорию и портал целиком. Менять статью портала может владелец "
                    + "или доверенный пользователь портала, глобальную — только оператор. If-Match с версией из GET "
                    + "защищает от потери чужого изменения.")
    @PutMapping("/{articleId}")
    @PreAuthorize("@knowledgeBaseAccessValidationService.canManage(#articleId)")
    public ResponseEntity<ArticleResponse> replaceArticle(@PathVariable Long articleId,
            @Valid @RequestBody UpdateArticleRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch)
            throws KnowledgeBaseException, PortalException {
        ArticleResponse response = knowledgeBaseManageService.replaceArticle(articleId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-article", response.version())).body(response);
    }

    @Operation(summary = "Частичное обновление статьи (PATCH)",
            description = "Меняет только переданные поля (null — не трогать). Менять статью портала может владелец "
                    + "или доверенный пользователь портала, глобальную — только оператор. If-Match с версией из GET "
                    + "обязателен для защиты от потери чужого изменения. "
                    + "Перевод статьи в глобальные через PATCH недоступен.")
    @PatchMapping("/{articleId}")
    @PreAuthorize("@knowledgeBaseAccessValidationService.canManage(#articleId)")
    public ResponseEntity<ArticleResponse> patchArticle(@PathVariable Long articleId,
            @Valid @RequestBody PatchArticleRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch)
            throws KnowledgeBaseException, PortalException {
        ArticleResponse response = knowledgeBaseManageService.patchArticle(articleId, request, ifMatch);
        return ResponseEntity.ok().eTag(EntityTag.of("kb-article", response.version())).body(response);
    }

    @Operation(summary = "Удаление статьи",
            description = "Мягкое удаление: статья скрывается из выдачи, строка остаётся в БД. Удалять статью портала "
                    + "может владелец или доверенный пользователь портала, глобальную — только оператор. "
                    + "Возвращает 204 без тела.")
    @DeleteMapping("/{articleId}")
    @PreAuthorize("@knowledgeBaseAccessValidationService.canManage(#articleId)")
    @ApiResponse(responseCode = "204", description = "Без содержимого (команда выполнена)")
    public ResponseEntity<Void> deleteArticle(@PathVariable Long articleId)
            throws KnowledgeBaseException, PortalException {
        knowledgeBaseManageService.deleteArticle(articleId);
        return ResponseEntity.noContent().build();
    }
}

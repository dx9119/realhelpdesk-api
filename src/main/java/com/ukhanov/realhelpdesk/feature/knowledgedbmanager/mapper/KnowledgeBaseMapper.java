package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseCategoryModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.ArticleResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CategoryResponse;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.dto.CreateArticleRequest;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface KnowledgeBaseMapper {

    // category и portal — отдельные параметры: клиент передаёт только id, сущности резолвит сервис,
    // иначе клиент подставил бы чужую категорию или чужой портал
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "question", source = "request.question")
    @Mapping(target = "answer", source = "request.answer")
    @Mapping(target = "category", source = "category")
    @Mapping(target = "portal", source = "portal")
    KnowledgeBaseArticleModel fromRequest(CreateArticleRequest request, KnowledgeBaseCategoryModel category,
            PortalModel portal);

    @Mapping(target = "categoryId", source = "article.category.id")
    @Mapping(target = "categoryName", source = "article.category.name")
    @Mapping(target = "portalId", source = "article.portal.id")
    ArticleResponse toResponse(KnowledgeBaseArticleModel article);

    CategoryResponse toResponse(KnowledgeBaseCategoryModel category);
}

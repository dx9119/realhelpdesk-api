package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
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
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.mapper.KnowledgeBaseMapper;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Жизненный цикл статей базы знаний: создание, замена, частичное обновление, мягкое удаление и поиск.
 *
 * <p>
 * Область видимости статьи задаёт и права на неё: статью портала меняет владелец или доверенный пользователь портала
 * ({@code allowedUserIds}), глобальную (portal = null) — только оператор. Роль всегда читается из БД через
 * {@link CurrentUserProvider}, поэтому понижение роли вступает в силу сразу, не дожидаясь истечения токена. Те же
 * правила объявлены в {@code @PreAuthorize} контроллера; проверки здесь повторяются для вызовов в обход HTTP.
 * </p>
 *
 * <p>
 * Категория и портал резолвятся из id до вызова маппера: клиент не передаёт сущности, иначе он мог бы подставить чужую
 * категорию или чужой портал.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class KnowledgeBaseManageService {

    /** Поля для сортировки: только колонки самой статьи, сортировка по ассоциации требовала бы лишнего join. */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "question", "id");

    private final KnowledgeBaseDomainService knowledgeBaseDomainService;
    private final KnowledgeBaseArticleRepository articleRepository;
    private final PortalDomainService portalDomainService;
    private final PortalRepository portalRepository;
    private final AccessValidationService accessValidationService;
    private final CurrentUserProvider currentUserProvider;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PaginationAdapter paginationAdapter;

    /**
     * Чтение статьи по id. Статья чужого приватного портала отдаётся как отсутствующая: {@code 404}, а не {@code 403},
     * — чтобы не раскрывать факт существования записи.
     */
    public ArticleResponse getArticle(Long articleId) throws KnowledgeBaseException, PortalException {
        KnowledgeBaseArticleModel article = knowledgeBaseDomainService.getArticleById(articleId);
        requireReadAccess(articleId, article.getPortal());
        return knowledgeBaseMapper.toResponse(article);
    }

    @Transactional
    public ArticleResponse createArticle(CreateArticleRequest request)
            throws KnowledgeBaseException, PortalException {
        Objects.requireNonNull(request, "CreateArticleRequest не должен быть null");

        KnowledgeBaseCategoryModel category = knowledgeBaseDomainService.getCategoryById(request.categoryId());
        PortalModel portal = resolvePortal(request.portalId(), true);

        if (knowledgeBaseDomainService.existsArticleByQuestion(request.portalId(), request.question())) {
            throw KnowledgeBaseException.conflict("Вопрос уже есть в базе знаний"
                    + (request.portalId() == null ? "" : " этого портала"));
        }

        KnowledgeBaseArticleModel article = knowledgeBaseMapper.fromRequest(request, category, portal);
        KnowledgeBaseArticleModel saved = knowledgeBaseDomainService.saveArticle(article);
        logger.info("Создана статья {} (портал {}, категория {})", saved.getId(), request.portalId(),
                category.getName());
        return knowledgeBaseMapper.toResponse(saved);
    }

    @Transactional
    public ArticleResponse replaceArticle(Long articleId, UpdateArticleRequest request, String ifMatch)
            throws KnowledgeBaseException, PortalException {
        Objects.requireNonNull(request, "UpdateArticleRequest не должен быть null");

        KnowledgeBaseArticleModel article = knowledgeBaseDomainService.getArticleById(articleId);
        requireManageAccess(article.getPortal());
        requireFreshVersion(article, ifMatch);

        KnowledgeBaseCategoryModel category = knowledgeBaseDomainService.getCategoryById(request.categoryId());
        PortalModel portal = resolvePortal(request.portalId(), true);
        requireQuestionFree(articleId, request.portalId(), request.question());

        article.setQuestion(request.question());
        article.setAnswer(request.answer());
        article.setCategory(category);
        article.setPortal(portal);

        KnowledgeBaseArticleModel saved = knowledgeBaseDomainService.saveArticle(article);
        logger.info("Статья {} заменена целиком", articleId);
        return knowledgeBaseMapper.toResponse(saved);
    }

    /**
     * Частичное обновление: меняются только переданные поля. Перевод статьи в глобальные (и обратно) здесь недоступен:
     * {@code portalId = null} означает «не трогать», а не «сделать глобальной» — для этого есть {@code PUT}.
     */
    @Transactional
    public ArticleResponse patchArticle(Long articleId, PatchArticleRequest request, String ifMatch)
            throws KnowledgeBaseException, PortalException {
        Objects.requireNonNull(request, "PatchArticleRequest не должен быть null");
        if (request.isEmpty()) {
            throw KnowledgeBaseException.badRequest("Не передано ни одного поля для изменения");
        }

        KnowledgeBaseArticleModel article = knowledgeBaseDomainService.getArticleById(articleId);
        requireManageAccess(article.getPortal());
        requireFreshVersion(article, ifMatch);

        if (request.question() != null && !Objects.equals(article.getQuestion(), request.question())) {
            requireQuestionFree(articleId, portalIdOf(article), request.question());
            article.setQuestion(request.question());
        }
        if (request.answer() != null && !Objects.equals(article.getAnswer(), request.answer())) {
            article.setAnswer(request.answer());
        }
        if (request.categoryId() != null && !Objects.equals(categoryIdOf(article), request.categoryId())) {
            article.setCategory(knowledgeBaseDomainService.getCategoryById(request.categoryId()));
        }
        if (request.portalId() != null && !Objects.equals(portalIdOf(article), request.portalId())) {
            article.setPortal(resolvePortal(request.portalId(), true));
        }

        KnowledgeBaseArticleModel saved = knowledgeBaseDomainService.saveArticle(article);
        logger.info("Статья {} обновлена частично (PATCH)", articleId);
        return knowledgeBaseMapper.toResponse(saved);
    }

    @Transactional
    public void deleteArticle(Long articleId) throws KnowledgeBaseException, PortalException {
        KnowledgeBaseArticleModel article = knowledgeBaseDomainService.getArticleById(articleId);
        requireManageAccess(article.getPortal());

        article.setDeleted(true);
        article.setTimeDelete(java.time.Instant.now());
        articleRepository.save(article);
        logger.info("Статья {} удалена мягко", articleId);
    }

    /**
     * Поиск статей: отдаёт глобальные плюс статьи порталов, доступных текущему пользователю. Явный {@code portalId} в
     * фильтрах проверяется на доступ, иначе поиск стал бы способом прочитать чужой портал.
     */
    public PageResponse<ArticleResponse> searchArticles(KnowledgeBaseSearchCriteria criteria,
            PageCriteria pageCriteria) throws KnowledgeBaseException, PortalException {

        Long userId = currentUserProvider.getCurrentUserId();
        logger.debug("Запрос на поиск статей базы знаний. Пользователь: {}, Поиск: '{}', Категория: {}, Портал: {}",
                userId, criteria.search(), criteria.categoryId(), criteria.portalId());

        PageRequest pageRequest = paginationAdapter.buildPageRequest(pageCriteria.page(), pageCriteria.size(),
                pageCriteria.sortBy(), pageCriteria.order(), SORTABLE_FIELDS);

        if (criteria.portalId() != null) {
            if (!accessValidationService.hasPortalAccess(criteria.portalId())) {
                throw KnowledgeBaseException.forbidden("Портал недоступен");
            }
            Set<Long> single = Set.of(criteria.portalId());
            return pageToResponse(knowledgeBaseDomainService.getArticlesPage(criteria, single, pageRequest));
        }

        Set<Long> accessiblePortalIds = getAccessiblePortalIds(userId);
        return pageToResponse(knowledgeBaseDomainService.getArticlesPage(criteria, accessiblePortalIds, pageRequest));
    }

    private PageResponse<ArticleResponse> pageToResponse(Page<KnowledgeBaseArticleModel> page) {
        return paginationAdapter.mapToResponse(page.map(knowledgeBaseMapper::toResponse));
    }

    /**
     * Портал статьи по id из запроса. Права на портал проверяет {@code @PreAuthorize} в контроллере
     * ({@code KnowledgeBaseAccessValidationService}); сервис повторяет проверку для вызовов в обход HTTP.
     */
    private PortalModel resolvePortal(Long portalId, boolean manage) throws KnowledgeBaseException, PortalException {
        if (portalId == null) {
            requireOperator();
            return null;
        }

        boolean allowed = manage
                ? accessValidationService.hasPortalManageAccess(portalId)
                : accessValidationService.hasPortalAccess(portalId);
        if (!allowed) {
            throw KnowledgeBaseException.forbidden(manage
                    ? "Изменять статьи портала может только его владелец или доверенный пользователь"
                    : "Портал недоступен");
        }
        return portalDomainService.getPortalById(portalId);
    }

    /** Права на изменение/удаление статьи: по её текущей области видимости. */
    private void requireManageAccess(PortalModel portal) throws KnowledgeBaseException, PortalException {
        if (portal == null) {
            requireOperator();
            return;
        }
        if (!accessValidationService.hasPortalManageAccess(portal.getId())) {
            throw KnowledgeBaseException.forbidden(
                    "Изменять статьи портала может только его владелец или доверенный пользователь");
        }
    }

    /**
     * Права на чтение статьи: глобальная видна всем, статья портала — только тем, кому доступен портал. Отказ — 404:
     * ответ не должен подтверждать существование чужой записи.
     */
    private void requireReadAccess(Long articleId, PortalModel portal)
            throws KnowledgeBaseException, PortalException {
        if (portal == null) {
            return;
        }
        if (!accessValidationService.hasPortalAccess(portal.getId())) {
            throw KnowledgeBaseException.notFound("Статья с ID " + articleId + " не найдена");
        }
    }

    private void requireOperator() throws KnowledgeBaseException {
        UserModel user = currentUserProvider.getCurrentUserModel();
        if (user.getUserRole() != UserRole.ROLE_OPERATOR) {
            logger.warn("Доступ запрещён: пользователь {} не оператор, глобальные статьи недоступны", user.getId());
            throw KnowledgeBaseException.forbidden("Глобальные статьи базы знаний создаёт и изменяет только оператор");
        }
    }

    private void requireQuestionFree(Long articleId, Long portalId, String question) throws KnowledgeBaseException {
        if (knowledgeBaseDomainService.existsArticleByQuestionExcept(articleId, portalId, question)) {
            throw KnowledgeBaseException.conflict("Вопрос уже есть в базе знаний");
        }
    }

    /** If-Match: клиент присылает версию, которую видел; несовпадение — 412 до записи, а не 409 после факта. */
    private void requireFreshVersion(KnowledgeBaseArticleModel article, String ifMatch)
            throws KnowledgeBaseException {
        if (!EntityTag.satisfiedBy(ifMatch, EntityTag.of("kb-article", article.getVersion()), article.getVersion())) {
            throw new KnowledgeBaseException(
                    "Статья уже изменена: данные устарели, обновите страницу и повторите",
                    HttpStatus.PRECONDITION_FAILED);
        }
    }

    // id читаются через getId() ассоциаций: portal/category LAZY, но в границах транзакции догрузка не нужна —
    // Hibernate отдаёт id из внешнего ключа без запроса
    private Long portalIdOf(KnowledgeBaseArticleModel article) {
        return article.getPortal() == null ? null : article.getPortal().getId();
    }

    private Long categoryIdOf(KnowledgeBaseArticleModel article) {
        return article.getCategory() == null ? null : article.getCategory().getId();
    }

    private Set<Long> getAccessiblePortalIds(Long userId) {
        List<PortalModel> ownedPortals = portalRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId);
        List<PortalModel> allowedPortals = portalRepository.findAllAccessibleByUserId(userId);
        List<Long> publicPortalIds = portalRepository.findPublicPortalIdsWithUserTickets(userId);

        Set<Long> portalIds = new HashSet<>();
        ownedPortals.stream().map(PortalModel::getId).forEach(portalIds::add);
        allowedPortals.stream().map(PortalModel::getId).forEach(portalIds::add);
        portalIds.addAll(publicPortalIds);

        return portalIds;
    }
}

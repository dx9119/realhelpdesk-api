package com.ukhanov.realhelpdesk.core.security.accesscontrol.service;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.domain.knowledgedb.service.KnowledgeBaseDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Права на статьи базы знаний для выражений {@code @PreAuthorize}.
 *
 * <p>
 * Право на статью определяется её текущей областью видимости: глобальная ({@code portal == null}) — только
 * {@code ROLE_OPERATOR}, статья портала — владелец портала или доверенный пользователь ({@code allowedUserIds}).
 * Публичный доступ права управления не даёт: {@link AccessValidationService#hasPortalManageAccess} его не учитывает.
 * </p>
 *
 * <p>
 * Чтение чужой статьи приватного портала маскируется под {@code 404}: ответ не должен подтверждать существование
 * записи. Роль читается из БД через {@link CurrentUserProvider}, поэтому понижение роли действует сразу.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeBaseAccessValidationService {

    private final AccessValidationService accessValidationService;
    private final KnowledgeBaseDomainService knowledgeBaseDomainService;
    private final CurrentUserProvider currentUserProvider;

    /** Создание статьи: в портал — владелец или доверенный, глобально — только оператор. */
    public boolean canCreate(Long portalId) throws PortalException {
        if (portalId == null) {
            return isOperator();
        }
        return accessValidationService.hasPortalManageAccess(portalId);
    }

    /** Изменение и удаление существующей статьи: права считаются по её текущей области видимости. */
    public boolean canManage(Long articleId) throws KnowledgeBaseException, PortalException {
        PortalModel portal = knowledgeBaseDomainService.getArticleById(articleId).getPortal();
        if (portal == null) {
            return isOperator();
        }
        return accessValidationService.hasPortalManageAccess(portal.getId());
    }

    /** Чтение статьи: глобальная видна всем, статья портала — только тем, кому доступен портал. */
    public boolean canRead(Long articleId) throws KnowledgeBaseException, PortalException {
        PortalModel portal = knowledgeBaseDomainService.getArticleById(articleId).getPortal();
        if (portal == null || accessValidationService.hasPortalAccess(portal.getId())) {
            return true;
        }
        throw KnowledgeBaseException.notFound("Статья с ID " + articleId + " не найдена");
    }

    /** Поиск по явному {@code portalId}: без фильтра область видимости считает сервис. */
    public boolean canSearch(Long portalId) throws PortalException {
        return portalId == null || accessValidationService.hasPortalAccess(portalId);
    }

    private boolean isOperator() {
        UserModel user = currentUserProvider.getCurrentUserModel();
        boolean operator = user.getUserRole() == UserRole.ROLE_OPERATOR;
        if (!operator) {
            logger.warn("Доступ запрещён: пользователь {} не оператор, глобальные статьи недоступны", user.getId());
        }
        return operator;
    }
}

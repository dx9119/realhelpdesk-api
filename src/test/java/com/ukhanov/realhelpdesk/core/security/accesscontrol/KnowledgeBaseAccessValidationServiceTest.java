package com.ukhanov.realhelpdesk.core.security.accesscontrol;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.KnowledgeBaseAccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.domain.knowledgedb.model.KnowledgeBaseArticleModel;
import com.ukhanov.realhelpdesk.domain.knowledgedb.service.KnowledgeBaseDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception.KnowledgeBaseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("База знаний: права владельца, доверенных и оператора")
class KnowledgeBaseAccessValidationServiceTest {

    private static final Long PORTAL_ID = 1L;
    private static final Long ARTICLE_ID = 100L;

    private UserModel owner;
    private UserModel trusted;
    private UserModel stranger;
    private UserModel operator;

    private PortalModel portal;
    private UserModel currentUser;
    private KnowledgeBaseAccessValidationService service;

    @BeforeEach
    void setUp() throws Exception {
        owner = user(10L, UserRole.ROLE_USER);
        trusted = user(11L, UserRole.ROLE_USER);
        stranger = user(12L, UserRole.ROLE_USER);
        operator = user(13L, UserRole.ROLE_OPERATOR);

        portal = new PortalModel();
        portal.setId(PORTAL_ID);
        portal.setOwner(owner);
        portal.setAllowedUserIds(new HashSet<>(Set.of(trusted.getId())));

        currentUser = owner;

        PortalDomainService portalDomainService = new PortalDomainService(mock(PortalRepository.class)) {
            @Override
            public PortalModel getPortalById(Long portalId) {
                return portal;
            }
        };

        KnowledgeBaseDomainService knowledgeBaseDomainService = mock(KnowledgeBaseDomainService.class);
        when(knowledgeBaseDomainService.getArticleById(ARTICLE_ID)).thenAnswer(invocation -> article(portal));

        CurrentUserProvider currentUserProvider = currentUserProvider();

        AccessValidationService accessValidationService = new AccessValidationService(portalDomainService,
                currentUserProvider);

        service = new KnowledgeBaseAccessValidationService(accessValidationService, knowledgeBaseDomainService,
                currentUserProvider);
    }

    @Test
    @DisplayName("canCreate: владелец и доверенный создают статью портала, посторонний — нет")
    void canCreate_portalArticle_ownerAndTrustedAllowed() throws Exception {

        // given
        currentUser = owner;

        // when / then
        assertThat(service.canCreate(PORTAL_ID)).isTrue();

        currentUser = trusted;
        assertThat(service.canCreate(PORTAL_ID)).isTrue();

        currentUser = stranger;
        assertThat(service.canCreate(PORTAL_ID)).isFalse();
    }

    @Test
    @DisplayName("canCreate: публичный портал не даёт права создавать статьи постороннему")
    void canCreate_publicPortalStranger_denied() throws Exception {

        // given
        portal.setPublic(true);
        currentUser = stranger;

        // when / then
        assertThat(service.canCreate(PORTAL_ID)).isFalse();
    }

    @Test
    @DisplayName("canCreate: глобальная статья — только оператор")
    void canCreate_globalArticle_operatorOnly() throws Exception {

        // given
        currentUser = operator;

        // when / then
        assertThat(service.canCreate(null)).isTrue();

        currentUser = trusted;
        assertThat(service.canCreate(null)).isFalse();
    }

    @Test
    @DisplayName("canManage: статью портала меняет доверенный, посторонний — нет")
    void canManage_portalArticle_trustedAllowed() throws Exception {

        // given
        currentUser = trusted;

        // when / then
        assertThat(service.canManage(ARTICLE_ID)).isTrue();

        currentUser = stranger;
        assertThat(service.canManage(ARTICLE_ID)).isFalse();
    }

    @Test
    @DisplayName("canManage: глобальную статью меняет только оператор")
    void canManage_globalArticle_operatorOnly() throws Exception {

        // given
        KnowledgeBaseDomainService globalArticleDomainService = mock(KnowledgeBaseDomainService.class);
        when(globalArticleDomainService.getArticleById(ARTICLE_ID)).thenAnswer(invocation -> article(null));

        CurrentUserProvider currentUserProvider = currentUserProvider();
        KnowledgeBaseAccessValidationService globalService = new KnowledgeBaseAccessValidationService(
                new AccessValidationService(mock(PortalDomainService.class), currentUserProvider),
                globalArticleDomainService, currentUserProvider);

        currentUser = operator;

        // when / then
        assertThat(globalService.canManage(ARTICLE_ID)).isTrue();

        currentUser = owner;
        assertThat(globalService.canManage(ARTICLE_ID)).isFalse();
    }

    @Test
    @DisplayName("canRead: статья чужого приватного портала маскируется под 404")
    void canRead_foreignPrivatePortal_notFound() {

        // given
        currentUser = stranger;

        // when / then
        assertThatThrownBy(() -> service.canRead(ARTICLE_ID))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("не найдена");
    }

    @Test
    @DisplayName("canRead: статья своего портала читается доверенным")
    void canRead_ownPortal_allowed() throws Exception {

        // given
        currentUser = trusted;

        // when / then
        assertThat(service.canRead(ARTICLE_ID)).isTrue();
    }

    @Test
    @DisplayName("canSearch: без фильтра по порталу — без проверки, с чужим portalId — отказ")
    void canSearch_portalFilter_scoped() throws Exception {

        // given
        currentUser = stranger;

        // when / then
        assertThat(service.canSearch(null)).isTrue();
        assertThat(service.canSearch(PORTAL_ID)).isFalse();
    }

    private CurrentUserProvider currentUserProvider() {
        return new CurrentUserProvider(null) {
            @Override
            public UserModel getCurrentUserModel() {
                return currentUser;
            }
        };
    }

    private KnowledgeBaseArticleModel article(PortalModel articlePortal) {
        KnowledgeBaseArticleModel article = new KnowledgeBaseArticleModel();
        article.setId(ARTICLE_ID);
        article.setPortal(articlePortal);
        return article;
    }

    private UserModel user(Long id, UserRole role) {
        UserModel user = new UserModel();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}

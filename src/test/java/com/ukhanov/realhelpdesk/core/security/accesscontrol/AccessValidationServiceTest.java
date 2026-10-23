package com.ukhanov.realhelpdesk.core.security.accesscontrol;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Управление порталом: владелец и доверенные пользователи")
class AccessValidationServiceTest {

    private static final Long PORTAL_ID = 1L;

    private UserModel owner;
    private UserModel trusted;
    private UserModel stranger;

    private PortalModel portal;
    private UserModel currentUser;
    private AccessValidationService service;

    @BeforeEach
    void setUp() {
        owner = user();
        trusted = user();
        stranger = user();

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

        CurrentUserProvider currentUserProvider = new CurrentUserProvider(null) {
            @Override
            public UserModel getCurrentUserModel() {
                return currentUser;
            }
        };

        service = new AccessValidationService(portalDomainService, currentUserProvider);
    }

    @Test
    @DisplayName("Владелец управляет порталом")
    void hasPortalManageAccess_ownerOnBoard_returnsTrue() throws Exception {
        // given
        currentUser = owner;

        // when / then
        assertThat(service.hasPortalManageAccess(PORTAL_ID)).isTrue();
    }

    @Test
    @DisplayName("Доверенный пользователь управляет порталом")
    void hasPortalManageAccess_trustedUser_returnsTrue() throws Exception {
        // given
        currentUser = trusted;

        // when / then
        assertThat(service.hasPortalManageAccess(PORTAL_ID)).isTrue();
    }

    @Test
    @DisplayName("Посторонний не управляет даже публичным порталом")
    void hasPortalManageAccess_strangerOnPublicPortal_returnsFalse() throws Exception {
        // given
        currentUser = stranger;
        // when
        portal.setPublic(true);

        // then
        assertThat(service.hasPortalManageAccess(PORTAL_ID)).isFalse();
        assertThat(service.hasPortalAccess(PORTAL_ID)).isTrue();
    }

    @Test
    @DisplayName("Посторонний не имеет доступа к приватному порталу")
    void hasPortalManageAccess_strangerOnPrivatePortal_returnsFalse() throws Exception {
        // given
        currentUser = stranger;

        // when / then
        assertThat(service.hasPortalManageAccess(PORTAL_ID)).isFalse();
        assertThat(service.hasPortalAccess(PORTAL_ID)).isFalse();
    }

    @Test
    @DisplayName("Публичный доступ не даёт прав управления (только владелец меняет isPublic)")
    void hasPortalOwner_trustedUser_returnsFalse() throws Exception {
        // given
        currentUser = trusted;
        // when
        portal.setPublic(true);

        // then
        assertThat(service.hasPortalOwner(PORTAL_ID)).isFalse();
        assertThat(service.hasPortalManageAccess(PORTAL_ID)).isTrue();
    }

    private long userSeq;

    private UserModel user() {
        UserModel userModel = new UserModel();
        userModel.setId(++userSeq);
        return userModel;
    }
}

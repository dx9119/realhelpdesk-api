package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.DeleteResult;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.UpdatePortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.mapper.PortalMapper;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Письма при работе с порталами: PortalManageService")
class PortalManageServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long PORTAL_ID = 100L;
    private static final String OWNER_EMAIL = "owner@example.com";

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private PortalDomainService portalDomainService;

    @Mock
    private PaginationAdapter paginationAdapter;

    @Mock
    private PortalUtilsService portalUtilsService;

    @Mock
    private AccessValidationService accessValidationService;

    @Mock
    private LimitService limitService;

    @Mock
    private UserDomainService userDomainService;

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private PortalHistoryService portalHistoryService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private PortalManageService service;

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    @BeforeEach
    void setUp() {
        service = new PortalManageService(currentUserProvider, portalDomainService, paginationAdapter, portalUtilsService,
                accessValidationService, limitService, userDomainService, emailDeliveryService, emailTemplates, notificationPublisher,
                portalHistoryService, Mappers.getMapper(PortalMapper.class), transactionTemplate);

        // без реального PlatformTransactionManager: execute() выполняет колбэк с null-статусом
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        UserModel owner = new UserModel();
        owner.setId(OWNER_ID);
        owner.setEmail(OWNER_EMAIL);
        owner.setEmailVerified(true);
        lenient().when(currentUserProvider.getCurrentUserModel()).thenReturn(owner);
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(OWNER_ID);
        lenient().when(limitService.hasUserReachedPortalLimit(owner)).thenReturn(false);
    }

    // ────────────────────────────────────────────────
    // createPortal
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Создание портала → письмо NEW_PORTAL участникам")
    void createPortal_validRequest_sendsPortalCreatedEmail() throws Exception {

        // given
        // when
        when(portalDomainService.isPortalExistByName(OWNER_ID, "Портал")).thenReturn(false);
        when(portalDomainService.savePortal(any())).thenAnswer(invocation -> {
            PortalModel portal = invocation.getArgument(0);
            portal.setId(PORTAL_ID);
            return portal;
        });

        CreatePortalResponse response = service.createPortal(request("Портал"));

        // then
        assertThat(response.id()).isEqualTo(PORTAL_ID);
        verify(emailDeliveryService).initNotifyPortalUsers(any(PortalModel.class), eq(emailTemplates.portalCreatedSubject(PORTAL_ID)),
                eq(emailTemplates.portalCreatedBody(PORTAL_ID)), eq(NotificationEvent.NEW_PORTAL));
    }

    @Test
    @DisplayName("Неподтверждённая почта: портал не создаётся, письма нет")
    void createPortal_emailNotVerified_throwsWithoutEmail() {

        // given
        // when
        currentUserProvider.getCurrentUserModel().setEmailVerified(false);

        // then
        assertThatThrownBy(() -> service.createPortal(request("Портал"))).isInstanceOf(UserManageException.class)
                .hasMessageContaining("Подтвердите ваш адрес электронной почты");

        verifyNoInteractions(emailDeliveryService, portalDomainService);
    }

    @Test
    @DisplayName("Достигнут лимит порталов: ошибка и письма нет")
    void createPortal_limitReached_throwsWithoutEmail() {

        // given
        // when
        when(limitService.hasUserReachedPortalLimit(currentUserProvider.getCurrentUserModel())).thenReturn(true);

        // then
        assertThatThrownBy(() -> service.createPortal(request("Портал"))).isInstanceOf(LimitException.class);

        verifyNoInteractions(emailDeliveryService);
    }

    // ────────────────────────────────────────────────
    // deletePortals
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Удаление портала владельцем → письмо PORTAL_DELETED")
    void deletePortals_owner_sendsPortalDeletedEmail() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        when(accessValidationService.hasPortalOwner(PORTAL_ID)).thenReturn(true);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        DeleteResult result = service.deletePortals(Set.of(PORTAL_ID));

        // then
        assertThat(result.count()).isEqualTo(1);
        verify(emailDeliveryService).initNotifyPortalUsers(eq(portal), eq(emailTemplates.deletedPortalSubject(PORTAL_ID)),
                eq(emailTemplates.deletedPortalBody(PORTAL_ID, OWNER_EMAIL)), eq(NotificationEvent.PORTAL_DELETED));
    }

    @Test
    @DisplayName("Удаление без прав владельца: письмо не уходит")
    void deletePortals_notOwner_sendsNoEmail() throws Exception {

        // given
        // when
        when(accessValidationService.hasPortalOwner(PORTAL_ID)).thenReturn(false);

        DeleteResult result = service.deletePortals(Set.of(PORTAL_ID));

        // then
        assertThat(result.count()).isZero();
        verifyNoInteractions(emailDeliveryService);
    }

    // ────────────────────────────────────────────────
    // история портала
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Смена видимости портала → история VISIBILITY_CHANGED (старое/новое значение)")
    void setPortalStatus_changed_recordsVisibilityHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.setPortalStatus(PORTAL_ID, true);

        // then
        assertThat(portal.isPublic()).isTrue();
        verify(portalHistoryService).record(eq(PORTAL_ID), eq(PortalHistoryEvent.VISIBILITY_CHANGED), eq(OWNER_ID), isNull(), isNull(),
                eq("visibility"), eq("false"), eq("true"));
    }

    @Test
    @DisplayName("Видимость не менялась → история не пишется")
    void setPortalStatus_unchanged_writesNoHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.setPortalStatus(PORTAL_ID, false);

        // then
        verify(portalHistoryService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Замена участников → история USERS_CHANGED со старым и новым составом")
    void addUserForPortal_changedMembership_recordsUsersHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        portal.setAllowedUserIds(new java.util.HashSet<>(Set.of(9L)));
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.addUserForPortal(PORTAL_ID, Set.of(9L, 10L));

        // then
        verify(portalHistoryService).record(eq(PORTAL_ID), eq(PortalHistoryEvent.USERS_CHANGED), eq(OWNER_ID), isNull(), isNull(),
                eq("users"), eq("9"), eq("9,10"));
    }

    @Test
    @DisplayName("Переименование и описание → история NAME_CHANGED и DESCRIPTION_CHANGED")
    void updatePortalInfo_changed_recordsHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        portal.setDescription("Старое");
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.updatePortalInfo(PORTAL_ID, new UpdatePortalInfoRequest("Новый", "Новое"), null);

        // then
        verify(portalHistoryService).record(eq(PORTAL_ID), eq(PortalHistoryEvent.NAME_CHANGED), eq(OWNER_ID), isNull(), isNull(),
                eq("name"), eq("Портал"), eq("Новый"));
        verify(portalHistoryService).record(eq(PORTAL_ID), eq(PortalHistoryEvent.DESCRIPTION_CHANGED), eq(OWNER_ID), isNull(), isNull(),
                eq("description"), eq("Старое"), eq("Новое"));
    }

    @Test
    @DisplayName("Данные не менялись → история не пишется")
    void updatePortalInfo_unchanged_writesNoHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        portal.setDescription("Старое");
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.updatePortalInfo(PORTAL_ID, new UpdatePortalInfoRequest("Портал", "Старое"), null);

        // then
        verify(portalHistoryService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Удаление портала владельцем → история PORTAL_DELETED с именем")
    void deletePortals_owner_recordsPortalDeletedHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        when(accessValidationService.hasPortalOwner(PORTAL_ID)).thenReturn(true);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.deletePortals(Set.of(PORTAL_ID));

        // then
        verify(portalHistoryService).record(eq(PORTAL_ID), eq(PortalHistoryEvent.PORTAL_DELETED), eq(OWNER_ID), isNull(), isNull(),
                eq("name"), eq("Портал"), isNull());
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private CreatePortalRequest request(String name) {
        return new CreatePortalRequest(name, null);
    }

    private PortalModel portal(Long id) {
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName("Портал");
        portal.setOwner(currentUserProvider.getCurrentUserModel());
        return portal;
    }
}

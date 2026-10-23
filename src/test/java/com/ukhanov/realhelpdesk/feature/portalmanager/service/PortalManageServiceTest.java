package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.pagination.service.PaginationAdapter;
import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.CreatePortalResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.UpdatePortalInfoRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.mapper.PortalMapper;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private LimitService limitService;

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private PortalHistoryService portalHistoryService;

    private PortalManageService service;

    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    @BeforeEach
    void setUp() {
        service = new PortalManageService(
                currentUserProvider, portalDomainService, paginationAdapter,
                limitService, emailDeliveryService, emailTemplates,
                notificationPublisher, portalHistoryService, Mappers.getMapper(PortalMapper.class));

        UserModel owner = new UserModel();
        owner.setId(OWNER_ID);
        owner.setEmail(OWNER_EMAIL);
        owner.setEmailVerified(true);
        when(currentUserProvider.getCurrentUserModel()).thenReturn(owner);
        when(currentUserProvider.getCurrentUserId()).thenReturn(OWNER_ID);
        when(limitService.hasUserReachedPortalLimit(owner)).thenReturn(false);
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
        verify(emailDeliveryService).initNotifyPortalUsers(any(PortalModel.class),
                eq(emailTemplates.portalCreatedSubject(PORTAL_ID)),
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
    // setPortalStatus
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Смена видимости портала → запись VISIBILITY_CHANGED в историю")
    void setPortalStatus_changed_recordsVisibilityHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.setPortalStatus(PORTAL_ID, true);

        // then
        assertThat(portal.isPublic()).isTrue();
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.VISIBILITY_CHANGED, OWNER_ID, null, null, "visibility", "false", "true"));
    }

    @Test
    @DisplayName("Видимость не менялась → история не пишется")
    void setPortalStatus_unchanged_writesNoHistory() throws Exception {

        // given
        // when
        PortalModel portal = portal(PORTAL_ID);
        portal.setPublic(false);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        service.setPortalStatus(PORTAL_ID, false);

        // then
        verify(portalHistoryService, never()).record(any(PortalHistoryRecordCommand.class));
    }

    // ────────────────────────────────────────────────
    // updatePortalInfo
    // ────────────────────────────────────────────────

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
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.NAME_CHANGED, OWNER_ID, null, null, "name", "Портал", "Новый"));
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.DESCRIPTION_CHANGED, OWNER_ID, null, null,
                "description", "Старое", "Новое"));
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
        verify(portalHistoryService, never()).record(any(PortalHistoryRecordCommand.class));
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private CreatePortalRequest request(String name) {
        return new CreatePortalRequest(name, null);
    }

    private PortalModel portal(Long id) {
        UserModel owner = currentUserProvider.getCurrentUserModel();
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName("Портал");
        portal.setOwner(owner);
        return portal;
    }
}

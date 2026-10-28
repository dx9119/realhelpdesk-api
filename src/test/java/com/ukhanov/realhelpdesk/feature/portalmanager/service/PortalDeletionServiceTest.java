package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.accesscontrol.service.AccessValidationService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.notificationmanager.service.NotificationPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Письма при удалении портала: PortalDeletionService")
class PortalDeletionServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long PORTAL_ID = 100L;
    private static final String USER_EMAIL = "owner@example.com";
    private static final String PORTAL_NAME = "Портал";

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private PortalDomainService portalDomainService;

    @Mock
    private AccessValidationService accessValidationService;

    @Mock
    private PortalHistoryService portalHistoryService;

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private PortalDeletionService service;

    @Captor
    private ArgumentCaptor<Map<String, Object>> modelCaptor;

    @Captor
    private ArgumentCaptor<PortalModel> portalCaptor;

    @BeforeEach
    void setUp() {
        service = new PortalDeletionService(currentUserProvider, portalDomainService, accessValidationService,
                portalHistoryService, emailDeliveryService, notificationPublisher, transactionTemplate);

        UserModel user = new UserModel();
        user.setId(USER_ID);
        user.setEmail(USER_EMAIL);
        when(currentUserProvider.getCurrentUserModel()).thenReturn(user);

        // TransactionTemplate.execute → callback по факту (без реальной транзакции)
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("Удалённый портал → письмо PORTAL_DELETED с portalId и deletedByEmail")
    void deletePortals_owner_sendsPortalDeletedEmail() throws Exception {

        // given
        PortalModel portal = portal(PORTAL_ID);
        when(accessValidationService.hasPortalOwner(PORTAL_ID)).thenReturn(true);
        when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        when(portalDomainService.savePortal(any())).thenReturn(portal);

        // when
        service.deletePortals(Set.of(PORTAL_ID));

        // then
        verify(emailDeliveryService).initNotifyPortalUsers(portalCaptor.capture(),
                eq(EmailTemplateId.PORTAL_DELETED), modelCaptor.capture(),
                eq(NotificationEvent.PORTAL_DELETED));

        assertThat(portalCaptor.getValue().getId()).isEqualTo(PORTAL_ID);
        assertThat(modelCaptor.getValue())
                .containsEntry("portalId", PORTAL_ID)
                .containsEntry("deletedByEmail", USER_EMAIL);
    }

    @Test
    @DisplayName("Нет прав владельца: портал не удаляется, письма нет")
    void deletePortals_notOwner_noEmail() throws Exception {

        // given
        when(accessValidationService.hasPortalOwner(PORTAL_ID)).thenReturn(false);

        // when
        service.deletePortals(Set.of(PORTAL_ID));

        // then
        verifyNoInteractions(emailDeliveryService, notificationPublisher);
    }

    @Test
    @DisplayName("Два портала: каждому уходит своё письмо PORTAL_DELETED со своим portalId")
    void deletePortals_multiplePortals_sendsEmailPerPortal() throws Exception {

        // given
        PortalModel first = portal(1L);
        PortalModel second = portal(2L);
        when(accessValidationService.hasPortalOwner(any())).thenReturn(true);
        when(portalDomainService.getPortalById(1L)).thenReturn(first);
        when(portalDomainService.getPortalById(2L)).thenReturn(second);
        when(portalDomainService.savePortal(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.deletePortals(Set.of(1L, 2L));

        // then
        verify(emailDeliveryService, times(2)).initNotifyPortalUsers(any(),
                eq(EmailTemplateId.PORTAL_DELETED), modelCaptor.capture(), eq(NotificationEvent.PORTAL_DELETED));

        assertThat(modelCaptor.getAllValues())
                .extracting(model -> model.get("portalId"))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    private PortalModel portal(Long id) {
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName(PORTAL_NAME);
        UserModel owner = currentUserProvider.getCurrentUserModel();
        portal.setOwner(owner);
        return portal;
    }
}

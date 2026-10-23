package com.ukhanov.realhelpdesk.feature.portalmanager.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.ukhanov.realhelpdesk.core.security.limiter.exception.LimitException;
import com.ukhanov.realhelpdesk.core.security.limiter.service.LimitService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service.LoginService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryEvent;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferRequestModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalTransferStatus;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalTransferRequestRepository;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalDomainService;
import com.ukhanov.realhelpdesk.domain.portal.service.PortalHistoryService;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferConfirmRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRejectRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferRequest;
import com.ukhanov.realhelpdesk.feature.portalmanager.dto.PortalTransferResponse;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Бизнес-инварианты передачи (запрос, история, смена владельца). Доставка оповещений проверена в
 * {@code PortalTransferNotificationServiceTest} — здесь только факт делегирования.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Передача владения порталом: PortalOwnerTransferService")
class PortalOwnerTransferServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long PROPOSED_ID = 8L;
    private static final Long MEMBER_ID = 9L;
    private static final Long STRANGER_ID = 99L;
    private static final Long PORTAL_ID = 100L;
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String PROPOSED_EMAIL = "new@example.com";
    private static final String GOOD_PASSWORD = "good-pass-1";
    private static final String BAD_PASSWORD = "bad-pass-1";
    private static final String PASSWORD_HASH = "$2a$hash";

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private PortalDomainService portalDomainService;

    @Mock
    private PortalTransferRequestRepository transferRequestRepository;

    @Mock
    private PortalHistoryService portalHistoryService;

    @Mock
    private UserDomainService userDomainService;

    @Mock
    private LoginService loginService;

    @Mock
    private LimitService limitService;

    @Mock
    private PortalTransferNotificationService notificationService;

    private PortalOwnerTransferService service;

    private UserModel owner;
    private UserModel proposed;
    private PortalModel portal;

    @BeforeEach
    void setUp() throws Exception {
        // Фабрика ответа — настоящая: проверка DTO входит в контракт сервиса, а не в заглушку
        service = new PortalOwnerTransferService(currentUserProvider, portalDomainService, transferRequestRepository,
                portalHistoryService,
                userDomainService, loginService, limitService, notificationService,
                new PortalTransferResponseFactory(portalDomainService, userDomainService));

        owner = user(OWNER_ID, OWNER_EMAIL);
        proposed = user(PROPOSED_ID, PROPOSED_EMAIL);
        portal = new PortalModel();
        portal.setId(PORTAL_ID);
        portal.setName("Портал");
        portal.setOwner(owner);
        portal.setAllowedUserIds(new HashSet<>(Set.of(MEMBER_ID, PROPOSED_ID)));

        lenient().when(currentUserProvider.getCurrentUserModel()).thenReturn(owner);
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(OWNER_ID);
        lenient().when(portalDomainService.getPortalById(PORTAL_ID)).thenReturn(portal);
        lenient().when(userDomainService.getUserById(OWNER_ID)).thenReturn(owner);
        lenient().when(userDomainService.getUserById(PROPOSED_ID)).thenReturn(proposed);
        lenient().when(loginService.isPasswordValid(GOOD_PASSWORD, PASSWORD_HASH)).thenReturn(true);
        lenient().when(transferRequestRepository.save(any(PortalTransferRequestModel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.empty());
    }

    /** Действующий пользователь — предлагаемый владелец (для confirm/reject). */
    private void asProposed() {
        when(currentUserProvider.getCurrentUserModel()).thenReturn(proposed);
        when(currentUserProvider.getCurrentUserId()).thenReturn(PROPOSED_ID);
    }

    // ────────────────────────────────────────────────
    // initiate
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Инициирование: запрос PENDING, история, участникам и новому владельцу in-app + email")
    void initiate_validRequest_createsPendingAndNotifies() throws Exception {

        // given
        // when
        when(userDomainService.getUserByEmail(PROPOSED_EMAIL)).thenReturn(proposed);

        PortalTransferResponse response = service.initiate(PORTAL_ID, initiateRequest());

        // then
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.reasonInitiator()).isEqualTo("Причина");
        assertThat(response.keepOldOwnerAsMember()).isTrue();
        assertThat(response.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(70)));

        ArgumentCaptor<PortalTransferRequestModel> captor = ArgumentCaptor.forClass(PortalTransferRequestModel.class);
        verify(transferRequestRepository).save(captor.capture());
        PortalTransferRequestModel saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PortalTransferStatus.PENDING);
        assertThat(saved.getInitiatorId()).isEqualTo(OWNER_ID);
        assertThat(saved.getProposedOwnerId()).isEqualTo(PROPOSED_ID);
        assertThat(saved.isKeepOldOwner()).isTrue();

        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_REQUESTED, OWNER_ID, PROPOSED_ID, "Причина", null, null, null));

        verify(notificationService).notifyTransferRequested(portal, saved, owner, proposed);
    }

    @Test
    @DisplayName("Инициирование чужим паролем: 401, ничего не сохраняется")
    void initiate_wrongPassword_rejects() throws Exception {

        // given
        // when
        PortalTransferRequest request = initiateRequest(PROPOSED_EMAIL, BAD_PASSWORD);

        // then
        assertThatThrownBy(() -> service.initiate(PORTAL_ID, request)).isInstanceOf(PortalException.class)
                .hasMessage("Неверный пароль");

        verifyNoInteractions(transferRequestRepository, portalHistoryService, notificationService);
    }

    @Test
    @DisplayName("Передача самому себе: ошибка, запроса нет")
    void initiate_selfTransfer_rejects() throws Exception {

        // given
        // when
        when(userDomainService.getUserByEmail(OWNER_EMAIL)).thenReturn(owner);
        PortalTransferRequest request = initiateRequest(OWNER_EMAIL, GOOD_PASSWORD);

        // then
        assertThatThrownBy(() -> service.initiate(PORTAL_ID, request)).isInstanceOf(PortalException.class)
                .hasMessageContaining("самому себе");

        verifyNoInteractions(transferRequestRepository, portalHistoryService);
    }

    @Test
    @DisplayName("Email без пользователя: 404, запроса нет")
    void initiate_unknownEmail_rejects() throws Exception {

        // given
        // when
        when(userDomainService.getUserByEmail(any())).thenThrow(new UsernameNotFoundException("нет"));

        // then
        assertThatThrownBy(() -> service.initiate(PORTAL_ID, initiateRequest())).isInstanceOf(PortalException.class)
                .hasMessageContaining("не найден");

        verifyNoInteractions(transferRequestRepository, portalHistoryService);
    }

    @Test
    @DisplayName("Неактивный предлагаемый владелец: ошибка, запроса нет")
    void initiate_blockedUser_rejects() throws Exception {

        // given
        // when
        proposed.setUserStatus(UserStatus.BLOCKED);
        when(userDomainService.getUserByEmail(PROPOSED_EMAIL)).thenReturn(proposed);

        // then
        assertThatThrownBy(() -> service.initiate(PORTAL_ID, initiateRequest())).isInstanceOf(PortalException.class)
                .hasMessageContaining("не активна");

        verifyNoInteractions(transferRequestRepository, portalHistoryService);
    }

    @Test
    @DisplayName("Второй активный запрос: 409, первый не трогаем")
    void initiate_secondPending_rejects() throws Exception {

        // given
        // when
        when(userDomainService.getUserByEmail(PROPOSED_EMAIL)).thenReturn(proposed);
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        // then
        assertThatThrownBy(() -> service.initiate(PORTAL_ID, initiateRequest())).isInstanceOf(PortalException.class)
                .hasMessageContaining("уже существует");

        verify(transferRequestRepository, never()).save(any());
        verify(portalHistoryService, never()).record(any(PortalHistoryRecordCommand.class));
    }

    @Test
    @DisplayName("Истёкший активный запрос закрывается как EXPIRED и создаётся новый")
    void initiate_stalePending_expiresAndCreatesNew() throws Exception {

        // given
        // when
        when(userDomainService.getUserByEmail(PROPOSED_EMAIL)).thenReturn(proposed);
        PortalTransferRequestModel stale = pendingTransfer(Instant.now().minus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(stale));

        PortalTransferResponse response = service.initiate(PORTAL_ID, initiateRequest());

        // then
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(stale.getStatus()).isEqualTo(PortalTransferStatus.EXPIRED);
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_EXPIRED, null, PROPOSED_ID, null, null, null, null));
        verify(notificationService).notifyTransferExpired(portal, stale, owner);
    }

    // ────────────────────────────────────────────────
    // confirm
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Подтверждение: владелец меняется, старый владелец остаётся участником, история и оповещения")
    void confirm_keepOldOwnerTrue_transfersOwnershipAndKeepsMember() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        pending.setKeepOldOwner(true);
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        PortalTransferResponse response = service.confirm(PORTAL_ID, confirmRequest());

        // then
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(portal.getOwner()).isSameAs(proposed);
        assertThat(portal.getAllowedUserIds()).containsExactlyInAnyOrder(OWNER_ID, MEMBER_ID);
        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.ACCEPTED);
        assertThat(pending.getReasonProposed()).isEqualTo("Принято");

        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_ACCEPTED, PROPOSED_ID, OWNER_ID, "Принято", null, null, null));

        verify(notificationService).notifyTransferAccepted(portal, pending, owner, proposed,
                Set.of(OWNER_ID, MEMBER_ID));
    }

    @Test
    @DisplayName("Подтверждение без keepOldOwner: старый владелец выходит из участников")
    void confirm_withoutKeepingOldOwner_removesHim() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        pending.setKeepOldOwner(false);
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.confirm(PORTAL_ID, confirmRequest());

        // then
        assertThat(portal.getOwner()).isSameAs(proposed);
        assertThat(portal.getAllowedUserIds()).containsExactly(MEMBER_ID);
    }

    @Test
    @DisplayName("Подтверждение не предлагаемым владельцем: 404 (существование запроса не раскрывается)")
    void confirm_byStranger_rejectsAsNotFound() throws Exception {

        // given
        // when
        when(currentUserProvider.getCurrentUserId()).thenReturn(STRANGER_ID);
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        // then
        assertThatThrownBy(() -> service.confirm(PORTAL_ID, confirmRequest())).isInstanceOf(PortalException.class)
                .hasMessageContaining("не найден");

        assertThat(portal.getOwner()).isSameAs(owner);
    }

    @Test
    @DisplayName("Подтверждение по истёкшему сроку: 409, запрос закрыт как EXPIRED")
    void confirm_expired_rejectsAndExpires() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().minus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        // then
        assertThatThrownBy(() -> service.confirm(PORTAL_ID, confirmRequest())).isInstanceOf(PortalException.class)
                .hasMessageContaining("истёк");

        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.EXPIRED);
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_EXPIRED, null, PROPOSED_ID, null, null, null, null));
    }

    @Test
    @DisplayName("Подтверждение чужим паролем: 401, портал не передан")
    void confirm_wrongPassword_rejects() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));
        PortalTransferConfirmRequest request = confirmRequest(BAD_PASSWORD);

        // then
        assertThatThrownBy(() -> service.confirm(PORTAL_ID, request)).isInstanceOf(PortalException.class)
                .hasMessage("Неверный пароль");

        assertThat(portal.getOwner()).isSameAs(owner);
        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.PENDING);
    }

    @Test
    @DisplayName("Лимит участников нового владельца: LimitException, портал не передан")
    void confirm_memberLimit_rejects() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        pending.setKeepOldOwner(true);
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(limitService.violatesSharedUserPortalLimit(eq(proposed), eq(portal), eq(2))).thenReturn(true);

        // then
        assertThatThrownBy(() -> service.confirm(PORTAL_ID, confirmRequest())).isInstanceOf(LimitException.class);

        assertThat(portal.getOwner()).isSameAs(owner);
        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.PENDING);
    }

    // ────────────────────────────────────────────────
    // reject / cancel / getPending
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Отклонение предлагаемым владельцем: REJECTED, история, причина инициатору")
    void reject_byProposedUser_marksRejectedAndNotifies() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        PortalTransferResponse response = service.reject(PORTAL_ID, rejectRequest());

        // then
        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.REJECTED);
        assertThat(pending.getReasonProposed()).isEqualTo("Отклоняю");

        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_REJECTED, PROPOSED_ID, OWNER_ID, "Отклоняю", null, null, null));
        verify(notificationService).notifyTransferRejected(portal, pending, owner, proposed);
    }

    @Test
    @DisplayName("Отзыв владельцем: CANCELLED, история, предлагаемому in-app + email")
    void cancel_pendingTransfer_marksCancelledAndNotifiesProposed() throws Exception {

        // given
        // when
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        PortalTransferResponse response = service.cancel(PORTAL_ID);

        // then
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.CANCELLED);

        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_CANCELLED, OWNER_ID, PROPOSED_ID, null, null, null, null));
        verify(notificationService).notifyTransferCancelled(portal, pending, owner);
    }

    @Test
    @DisplayName("Отзыв не владельцем: 403")
    void cancel_byStranger_rejects() throws Exception {

        // given
        // when
        when(currentUserProvider.getCurrentUserModel()).thenReturn(memberAsCurrent());

        // then
        assertThatThrownBy(() -> service.cancel(PORTAL_ID)).isInstanceOf(PortalException.class)
                .hasMessageContaining("только владелец");
    }

    @Test
    @DisplayName("GET активного запроса: виден предлагаемому владельцу")
    void getPending_proposedUser_returnsPendingTransfer() throws Exception {

        // given
        // when
        asProposed();
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        PortalTransferResponse response = service.getPending(PORTAL_ID);

        // then
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.proposedOwnerEmail()).isEqualTo(PROPOSED_EMAIL);
    }

    @Test
    @DisplayName("GET активного запроса постороннему: 404")
    void getPending_stranger_seesNotFound() throws Exception {

        // given
        // when
        when(currentUserProvider.getCurrentUserId()).thenReturn(STRANGER_ID);
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().plus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        // then
        assertThatThrownBy(() -> service.getPending(PORTAL_ID)).isInstanceOf(PortalException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("GET без активного запроса: 404")
    void getPending_withoutRequest_seesNotFound() throws Exception {

        // given / when / then
        assertThatThrownBy(() -> service.getPending(PORTAL_ID)).isInstanceOf(PortalException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("GET истёкшего запроса: ленивое закрытие и 404")
    void getPending_expired_closesAndNotFound() throws Exception {

        // given
        // when
        PortalTransferRequestModel pending = pendingTransfer(Instant.now().minus(Duration.ofHours(1)));
        when(transferRequestRepository.findByPortalIdAndStatus(PORTAL_ID, PortalTransferStatus.PENDING))
                .thenReturn(Optional.of(pending));

        // then
        assertThatThrownBy(() -> service.getPending(PORTAL_ID)).isInstanceOf(PortalException.class)
                .hasMessageContaining("не найден");

        assertThat(pending.getStatus()).isEqualTo(PortalTransferStatus.EXPIRED);
        verify(portalHistoryService).record(new PortalHistoryRecordCommand(PORTAL_ID,
                PortalHistoryEvent.TRANSFER_EXPIRED, null, PROPOSED_ID, null, null, null, null));
    }

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    private UserModel user(Long id, String email) {
        UserModel user = new UserModel();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Имя" + id);
        user.setLastName("Фамилия" + id);
        user.setPasswordHash(PASSWORD_HASH);
        user.setUserStatus(UserStatus.ACTIVE);
        return user;
    }

    private UserModel memberAsCurrent() {
        return user(MEMBER_ID, "member@example.com");
    }

    private PortalTransferRequestModel pendingTransfer(Instant expiresAt) {
        PortalTransferRequestModel transfer = new PortalTransferRequestModel();
        transfer.setId(1L);
        transfer.setPortalId(PORTAL_ID);
        transfer.setInitiatorId(OWNER_ID);
        transfer.setProposedOwnerId(PROPOSED_ID);
        transfer.setReasonInitiator("Причина");
        transfer.setKeepOldOwner(true);
        transfer.setStatus(PortalTransferStatus.PENDING);
        transfer.setExpiresAt(expiresAt);
        return transfer;
    }

    private PortalTransferRequest initiateRequest() {
        return initiateRequest(PROPOSED_EMAIL, GOOD_PASSWORD);
    }

    private PortalTransferRequest initiateRequest(String email, String password) {
        return new PortalTransferRequest(email, password, "Причина", true);
    }

    private PortalTransferConfirmRequest confirmRequest() {
        return confirmRequest(GOOD_PASSWORD);
    }

    private PortalTransferConfirmRequest confirmRequest(String password) {
        return new PortalTransferConfirmRequest(password, "Принято");
    }

    private PortalTransferRejectRequest rejectRequest() {
        return new PortalTransferRejectRequest("Отклоняю");
    }
}

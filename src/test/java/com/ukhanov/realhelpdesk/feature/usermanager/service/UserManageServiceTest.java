package com.ukhanov.realhelpdesk.feature.usermanager.service;

import jakarta.persistence.EntityNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;
import com.ukhanov.realhelpdesk.core.observability.AuthMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserPlatformSource;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.NewPasswdRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.RecoveryRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;
import com.ukhanov.realhelpdesk.feature.usermanager.mapper.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса профиля пользователя (UserManageService)")
class UserManageServiceTest {

    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private UserDomainService userDomainService;
    @Mock
    private EmailDeliveryService emailDeliveryService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private GetTokenService getTokenService;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);
    private final EmailTemplates emailTemplates = EmailTemplatesFixture.emailTemplates();

    private UserManageService service;

    @BeforeEach
    void setUp() {
        service = new UserManageService(currentUserProvider, userDomainService, emailDeliveryService, passwordEncoder,
                new AuthMetrics(new SimpleMeterRegistry()), getTokenService, emailTemplates, userMapper);
    }

    // ────────────────────────────────────────────────────────────────
    // getUserInfo
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserInfo → возвращает данные текущего пользователя в маппинге")
    void getUserInfo_currentUserExists_returnsMappedResponse() {
        // given
        UserModel user = createDefaultUser();
        when(currentUserProvider.getCurrentUserModel()).thenReturn(user);

        // when
        UserInfoResponse response = service.getUserInfo();

        // then
        assertThat(response.firstName()).isEqualTo("firstName");
        assertThat(response.email()).isEqualTo("email@example.com");
        assertThat(response).usingRecursiveComparison().isEqualTo(userMapper.toResponse(user));
        verifyNoInteractions(emailDeliveryService, passwordEncoder, getTokenService);
    }

    // ────────────────────────────────────────────────────────────────
    // updateUserInfo
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateUserInfo → сохраняет новые поля и возвращает обновлённого пользователя")
    void updateUserInfo_validRequest_updatesFieldsAndReturnsUpdatedUser() throws UserManageException {
        // given
        UserModel existing = createDefaultUser();
        when(currentUserProvider.getCurrentUserModel()).thenReturn(existing);

        UserModel saved = createDefaultUser();
        saved.setFirstName("NewFirst");
        saved.setLastName("NewLast");
        when(userDomainService.saveUser(any())).thenReturn(saved);

        UserInfoRequest request = new UserInfoRequest("NewFirst", "NewLast", "NewMiddle", "NewInfo");

        // when
        UserInfoResponse response = service.updateUserInfo(request, null);

        // then
        assertThat(response.firstName()).isEqualTo("NewFirst");
        assertThat(response.lastName()).isEqualTo("NewLast");
        verify(userDomainService).saveUser(argThat(user -> "NewFirst".equals(user.getFirstName()) && "NewLast".equals(user.getLastName())
                && "NewMiddle".equals(user.getMiddleName()) && "NewInfo".equals(user.getAdditionalInfo())));
    }

    @Test
    @DisplayName("updateUserInfo → null-запрос → NPE")
    void updateUserInfo_nullRequest_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.updateUserInfo(null, null)).isInstanceOf(NullPointerException.class);
    }

    // ────────────────────────────────────────────────────────────────
    // sendResetLink
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendResetLink → неизвестный email → EntityNotFoundException, письмо не отправляется")
    void sendResetLink_unknownEmail_throwsEntityNotFound() {
        // given
        when(userDomainService.getUserByEmail("unknown@example.com")).thenThrow(new EntityNotFoundException("User not found"));
        RecoveryRequest request = new RecoveryRequest("unknown@example.com");

        // when / then
        assertThatThrownBy(() -> service.sendResetLink(request)).isInstanceOf(EntityNotFoundException.class);
        verifyNoMoreInteractions(emailDeliveryService);
    }

    @Test
    @DisplayName("sendResetLink → null-запрос → NPE")
    void sendResetLink_nullRequest_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.sendResetLink(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("sendResetLink → известный email → письмо со свежим кодом сброса")
    void sendResetLink_knownEmail_sendsPasswordResetEmailWithFreshToken() throws Exception {
        // given
        UserModel user = createDefaultUser();
        when(userDomainService.getUserByEmail("email@example.com")).thenReturn(user);

        // when
        service.sendResetLink(new RecoveryRequest("email@example.com"));

        // then
        verify(userDomainService).saveUser(user);
        assertThat(user.getRecoveryPasswdToken()).isNotEqualTo(88L);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailDeliveryService).sendEmail(eq("email@example.com"), eq(emailTemplates.passwordResetSubject()), bodyCaptor.capture(),
                eq(NotificationEvent.RECOVERY_PASSWORD), eq(user.getId()));
        verifyNoMoreInteractions(emailDeliveryService);
        assertThat(bodyCaptor.getValue()).contains("/pass-reset?code=" + user.getRecoveryPasswdToken())
                .contains(EmailTemplatesFixture.DOMAIN);
    }

    // ────────────────────────────────────────────────────────────────
    // setNewPasswd
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setNewPasswd → меняет пароль и отзывает старые токены")
    void setNewPasswd_validCode_updatesPasswordAndInvalidatesOldTokens() throws Exception {
        // given
        Long code = 55L;
        UserModel user = createDefaultUser();
        user.setRecoveryPasswdToken(code);
        int tokenVersionBefore = user.getTokenVersion();
        when(userDomainService.getUserByRecoveryPasswdToken(code)).thenReturn(user);
        // Старые активные refresh-токены (с разных устройств) отзываются одним bulk-UPDATE
        when(getTokenService.revokeActiveRefreshTokens(user, TokenStatus.PASSWD_CHANGE)).thenReturn(2);
        when(passwordEncoder.encode("newStrongPass123")).thenReturn("encodedNewPass");
        NewPasswdRequest request = new NewPasswdRequest("newStrongPass123");

        // when
        service.setNewPasswd(code, request);

        // then
        verify(passwordEncoder).encode("newStrongPass123");

        ArgumentCaptor<UserModel> userCaptor = ArgumentCaptor.forClass(UserModel.class);
        verify(userDomainService).saveUser(userCaptor.capture());
        UserModel savedUser = userCaptor.getValue();
        assertThat(savedUser.getPasswordHash()).isEqualTo("encodedNewPass");
        assertThat(savedUser.getRecoveryPasswdToken()).isNotEqualTo(code);
        assertThat(savedUser.getTokenVersion()).isEqualTo(tokenVersionBefore + 1);
        verify(getTokenService).revokeActiveRefreshTokens(user, TokenStatus.PASSWD_CHANGE);
    }

    @Test
    @DisplayName("setNewPasswd → неизвестный код → EntityNotFoundException")
    void setNewPasswd_unknownCode_throwsEntityNotFound() {
        // given
        Long wrongCode = 66L;
        when(userDomainService.getUserByRecoveryPasswdToken(wrongCode)).thenThrow(new EntityNotFoundException("Token not found"));

        // when / then
        assertThatThrownBy(() -> service.setNewPasswd(wrongCode, new NewPasswdRequest("pass"))).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("setNewPasswd → null-запрос → NPE")
    void setNewPasswd_nullRequest_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.setNewPasswd(55L, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("setNewPasswd → null-код → NPE")
    void setNewPasswd_nullCode_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.setNewPasswd(null, new NewPasswdRequest("pass"))).isInstanceOf(NullPointerException.class);
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    private UserModel createDefaultUser() {
        UserModel user = new UserModel();
        user.setId(77L);
        user.setEmail("email@example.com");
        user.setFirstName("firstName");
        user.setLastName("lastName");
        user.setMiddleName("middleName");
        user.setAdditionalInfo("additionalInfo");
        user.setPasswordHash("oldHash");
        user.setUserStatus(UserStatus.ACTIVE);
        user.setUserRole(UserRole.ROLE_NONE);
        user.setUserExternalSource(UserPlatformSource.LOCAL);
        user.setRecoveryPasswdToken(88L);
        return user;
    }
}

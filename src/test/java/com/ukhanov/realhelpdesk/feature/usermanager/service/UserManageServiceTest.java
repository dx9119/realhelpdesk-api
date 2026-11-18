package com.ukhanov.realhelpdesk.feature.usermanager.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserPlatformSource;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.PatchUserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;
import com.ukhanov.realhelpdesk.feature.usermanager.mapper.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса профиля пользователя (UserManageService)")
class UserManageServiceTest {

    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private UserDomainService userDomainService;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    private UserManageService service;

    @BeforeEach
    void setUp() {
        service = new UserManageService(currentUserProvider, userDomainService, userMapper);
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
        verifyNoInteractions(userDomainService);
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
        verify(userDomainService)
                .saveUser(argThat(user -> "NewFirst".equals(user.getFirstName()) && "NewLast".equals(user.getLastName())
                        && "NewMiddle".equals(user.getMiddleName()) && "NewInfo".equals(user.getAdditionalInfo())));
    }

    @Test
    @DisplayName("updateUserInfo → null-запрос → NPE")
    void updateUserInfo_nullRequest_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.updateUserInfo(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("updateUserInfo → устаревший If-Match → 412, сохранение не выполняется")
    void updateUserInfo_staleIfMatch_throwsPreconditionFailed() {
        // given
        UserModel existing = createDefaultUser();
        when(currentUserProvider.getCurrentUserModel()).thenReturn(existing);
        UserInfoRequest request = new UserInfoRequest("NewFirst", "NewLast", null, null);

        // when / then
        assertThatThrownBy(() -> service.updateUserInfo(request, "user-9999"))
                .isInstanceOf(UserManageException.class)
                .hasMessageContaining("устарели");
        verifyNoMoreInteractions(userDomainService);
    }

    // ────────────────────────────────────────────────────────────────
    // patchUserInfo
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("patchUserInfo → меняются только переданные поля")
    void patchUserInfo_partialRequest_updatesOnlyPresentFields() throws UserManageException {
        // given
        UserModel existing = createDefaultUser();
        when(currentUserProvider.getCurrentUserModel()).thenReturn(existing);
        when(userDomainService.saveUser(any())).thenReturn(existing);

        // when
        service.patchUserInfo(new PatchUserInfoRequest("Patched", null, null, null),
                EntityTag.of("user", existing.getVersion()));

        // then
        verify(userDomainService).saveUser(argThat(user -> "Patched".equals(user.getFirstName())
                && "lastName".equals(user.getLastName()) && "middleName".equals(user.getMiddleName())
                && "additionalInfo".equals(user.getAdditionalInfo())));
    }

    @Test
    @DisplayName("patchUserInfo → пустой запрос → UserManageException, сохранение не выполняется")
    void patchUserInfo_emptyRequest_throwsUserManageException() {
        // given
        PatchUserInfoRequest request = new PatchUserInfoRequest(null, null, null, null);

        // when / then
        assertThatThrownBy(() -> service.patchUserInfo(request, null)).isInstanceOf(UserManageException.class)
                .hasMessageContaining("ни одного поля");
        verifyNoInteractions(userDomainService);
    }

    @Test
    @DisplayName("patchUserInfo → null-запрос → NPE")
    void patchUserInfo_nullRequest_throwsNpe() {
        // given / when / then
        assertThatThrownBy(() -> service.patchUserInfo(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("patchUserInfo → устаревший If-Match → 412, сохранение не выполняется")
    void patchUserInfo_staleIfMatch_throwsPreconditionFailed() {
        // given
        UserModel existing = createDefaultUser();
        when(currentUserProvider.getCurrentUserModel()).thenReturn(existing);

        // when / then
        assertThatThrownBy(() -> service.patchUserInfo(new PatchUserInfoRequest(null, null, null, "NewInfo"), "42"))
                .isInstanceOf(UserManageException.class)
                .hasMessageContaining("устарели");
        verifyNoMoreInteractions(userDomainService);
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    private UserModel createDefaultUser() {
        UserModel user = new UserModel();
        user.setId(77L);
        user.setVersion(0);
        user.setEmail("email@example.com");
        user.setFirstName("firstName");
        user.setLastName("lastName");
        user.setMiddleName("middleName");
        user.setAdditionalInfo("additionalInfo");
        user.setUserStatus(UserStatus.ACTIVE);
        user.setUserRole(UserRole.ROLE_NONE);
        user.setUserPlatformSource(UserPlatformSource.KEYCLOAK);
        return user;
    }
}

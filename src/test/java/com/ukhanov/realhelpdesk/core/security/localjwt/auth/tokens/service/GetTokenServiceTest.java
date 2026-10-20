package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokenBearerResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.user.SecurityUser;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Ротация refresh-токенов при выдаче новой пары")
class GetTokenServiceTest {

    private static final String EMAIL = "user@test.local";
    private static final String NEW_REFRESH = "new-refresh-token";

    private JwtRefreshTokenRepository jwtRefreshTokenRepository;
    private SaveTokenService saveTokenService;
    private GetTokenService service;
    private UserModel user;

    @BeforeEach
    void setUp() {
        GenTokenService genTokenService = mock(GenTokenService.class);
        saveTokenService = mock(SaveTokenService.class);
        jwtRefreshTokenRepository = mock(JwtRefreshTokenRepository.class);

        service = new GetTokenService(genTokenService, saveTokenService, jwtRefreshTokenRepository, mock(FindTokenService.class),
                mock(ValidTokenService.class), mock(DecodeTokenService.class));

        user = new UserModel();
        user.setId(7L);
        user.setEmail(EMAIL);

        TokenBearerResponse access = new TokenBearerResponse("access-token");
        when(genTokenService.generateAccessJwtToken(any(SecurityUser.class))).thenReturn(access);

        RefreshTokenModel newToken = new RefreshTokenModel();
        newToken.setRawToken(NEW_REFRESH);
        when(genTokenService.generateRefreshJwtToken(any(SecurityUser.class))).thenReturn(newToken);
    }

    @Test
    @DisplayName("Предыдущие активные refresh-токены отзываются bulk-UPDATE — старый токен перестаёт быть валидным")
    void getNewTokens_existingActiveTokens_bulkRevokesThem() {

        // given
        // when
        when(jwtRefreshTokenRepository.updateStatusByEmailAndStatus(EMAIL, TokenStatus.ACTIVE, TokenStatus.REVOKED)).thenReturn(2);

        TokensResponse response = service.getNewTokens(user);

        // then
        assertThat(response.refreshToken()).isEqualTo(NEW_REFRESH);
        // прежние токены отзываются одним UPDATE, без поштучного сохранения сущностей
        verify(jwtRefreshTokenRepository).updateStatusByEmailAndStatus(EMAIL, TokenStatus.ACTIVE, TokenStatus.REVOKED);
        verify(saveTokenService, times(1)).saveRefreshToken(any(RefreshTokenModel.class));
    }

    @Test
    @DisplayName("Активных токенов нет — отзывать нечего, сохраняется только новый")
    void getNewTokens_noActiveTokens_savesOnlyNewToken() {

        // given
        // when
        service.getNewTokens(user);

        // then
        verify(jwtRefreshTokenRepository).updateStatusByEmailAndStatus(EMAIL, TokenStatus.ACTIVE, TokenStatus.REVOKED);
        verify(saveTokenService, times(1)).saveRefreshToken(any(RefreshTokenModel.class));
        verify(saveTokenService, never()).saveRefreshToken(argThat(token -> token.getStatus() == TokenStatus.REVOKED));
    }

    @Test
    @DisplayName("Смена пароля: bulk-отзыв активных токенов со статусом PASSWD_CHANGE")
    void revokeActiveRefreshTokens_passwdChange_usesBulkUpdate() {

        // given
        // when
        when(jwtRefreshTokenRepository.updateStatusByEmailAndStatus(EMAIL, TokenStatus.ACTIVE, TokenStatus.PASSWD_CHANGE)).thenReturn(3);

        int revoked = service.revokeActiveRefreshTokens(user, TokenStatus.PASSWD_CHANGE);

        // then
        assertThat(revoked).isEqualTo(3);
        verify(jwtRefreshTokenRepository).updateStatusByEmailAndStatus(EMAIL, TokenStatus.ACTIVE, TokenStatus.PASSWD_CHANGE);
        verify(saveTokenService, never()).saveRefreshToken(any(RefreshTokenModel.class));
    }
}

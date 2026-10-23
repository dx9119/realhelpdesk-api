package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.exception.InfrastructureExceptionHandler;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.RefreshTokenCleanupService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.RefreshTokenCleanupService.CleanupResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("REST-контракт операторской очистки токенов (TokenOperatorController)")
class TokenOperatorControllerTest {

    @Mock
    private RefreshTokenCleanupService refreshTokenCleanupService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcTester.of(List.of(new TokenOperatorController(refreshTokenCleanupService)),
                builder -> builder
                        .setControllerAdvice(new GlobalExceptionHandler(), new InfrastructureExceptionHandler())
                        .build());
    }

    @Test
    @DisplayName("POST /operator/tokens/cleanup → 200 со счётчиками удалённых отозванных и батчей")
    void cleanupEndpoint_success_returns200WithCounters() {
        // given
        when(refreshTokenCleanupService.cleanRevokedTokens()).thenReturn(new CleanupResult(42, 3));

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/operator/tokens/cleanup").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.revokedDeleted").asNumber().isEqualTo(42);
        assertThat(result).bodyJson().extractingPath("$.batches").asNumber().isEqualTo(3);

        verify(refreshTokenCleanupService).cleanRevokedTokens();
    }
}

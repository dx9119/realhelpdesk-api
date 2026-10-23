package com.ukhanov.realhelpdesk.core.security.localjwt.auth;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;
import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.controller.AuthController;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service.LoginService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.logout.service.LogoutService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.refresh.service.RefreshService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.service.RegistrationService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtProperties;
import com.ukhanov.realhelpdesk.feature.usermanager.service.UserManageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Max-Age auth-cookie берётся из {@code jwt.*-expiration-seconds} (секунды), а не из константы в коде: срок cookie
 * обязан совпадать со сроком жизни самого токена, иначе cookie переживёт токен либо исчезнет раньше него. Секунды в
 * конфиге — чтобы разные сроки можно было проверять, не меняя код.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Max-Age auth-cookie из jwt.*-expiration-seconds")
class AuthControllerCookieMaxAgeTest {

    @Mock
    private RegistrationService registrationService;
    @Mock
    private LoginService loginService;
    @Mock
    private LogoutService logoutService;
    @Mock
    private GetTokenService getTokenService;
    @Mock
    private RefreshService refreshService;
    @Mock
    private UserManageService userManageService;

    private JwtProperties jwtProperties;
    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpirationSeconds(90);
        jwtProperties.setRefreshTokenExpirationSeconds(7200);
        mockMvc = MockMvcTester.of(
                new AuthController(registrationService, loginService, logoutService, getTokenService, refreshService,
                        jwtProperties, userManageService, new ClientIpResolver(new ClientIpProperties())));
    }

    @Test
    @DisplayName("Вход: Max-Age обеих cookie равен сроку токена из конфига, в секундах")
    void login_configuredTtl_returnsMatchingMaxAge() throws Exception {
        // given
        when(loginService.processLogin(any())).thenReturn(new TokensResponse("access", "refresh", null));

        // when
        List<String> cookies = login();

        // then
        assertThat(maxAgeOf(cookies, "accessToken")).isEqualTo(90);
        assertThat(maxAgeOf(cookies, "refreshToken")).isEqualTo(7200);
    }

    @Test
    @DisplayName("Маленький срок из конфига уходит в cookie без пересчёта в минуты")
    void login_smallTtlPassedThrough_returnsSecondsWithoutRounding() throws Exception {
        // given
        jwtProperties.setAccessTokenExpirationSeconds(5);
        jwtProperties.setRefreshTokenExpirationSeconds(11);
        when(loginService.processLogin(any())).thenReturn(new TokensResponse("access", "refresh", null));

        // when
        List<String> cookies = login();

        // then
        assertThat(maxAgeOf(cookies, "accessToken")).isEqualTo(5);
        assertThat(maxAgeOf(cookies, "refreshToken")).isEqualTo(11);
    }

    private List<String> login() {
        return mockMvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"e2e@example.com\",\"password\":\"Passw0rd!123\"}")
                .exchange()
                .getResponse()
                .getHeaders(HttpHeaders.SET_COOKIE);
    }

    private long maxAgeOf(List<String> cookies, String name) {
        String cookie = cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElseThrow();
        return Long.parseLong(cookie.replaceAll(".*[Mm]ax-[Aa]ge=(\\d+).*", "$1"));
    }
}

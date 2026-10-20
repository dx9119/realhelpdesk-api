package com.ukhanov.realhelpdesk.core.security.localjwt.auth;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;
import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.login.service.LoginService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.logout.service.LogoutService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.refresh.service.RefreshService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.service.RegistrationService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtProperties;
import com.ukhanov.realhelpdesk.feature.usermanager.service.UserManageService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Атрибут Domain у auth-cookie из jwt.cookie.domain: задан — общий для поддоменов (файлы на отдельном домене); пусто — cookie host-only,
 * как раньше.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Атрибут Domain у auth-cookie (jwt.cookie.domain)")
class AuthControllerCookieDomainTest {

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
        mockMvc = MockMvcTester.of(new AuthController(registrationService, loginService, logoutService, getTokenService, refreshService,
                jwtProperties, userManageService, new ClientIpResolver(new ClientIpProperties())));
    }

    @Test
    @DisplayName("jwt.cookie.domain задан — обе cookie (access и refresh) несут Domain=...")
    void deleteSession_domainConfigured_cookiesCarryDomain() {
        // given
        jwtProperties.getCookie().setDomain(".example.com");

        // when
        List<String> cookies = setCookies();

        // then
        assertThat(cookies).hasSize(2).allSatisfy(cookie -> assertThat(cookie).contains("Domain=.example.com"));
        assertThat(cookies.get(0)).startsWith("accessToken=");
        assertThat(cookies.get(1)).startsWith("refreshToken=");
    }

    @Test
    @DisplayName("jwt.cookie.domain пуст — атрибут Domain не ставится, cookie остаётся host-only")
    void deleteSession_domainAbsent_cookiesStayHostOnly() {
        // given / when
        List<String> cookies = setCookies();

        // then
        assertThat(cookies).hasSize(2).noneSatisfy(cookie -> assertThat(cookie).containsIgnoringCase("Domain="));
    }

    private List<String> setCookies() {
        return mockMvc.delete().uri("/api/v1/auth/session").exchange().getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }
}

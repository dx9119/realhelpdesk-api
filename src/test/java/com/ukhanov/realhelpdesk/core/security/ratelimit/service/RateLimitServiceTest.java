package com.ukhanov.realhelpdesk.core.security.ratelimit.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
    }

    @Test
    @DisplayName("Запросы в пределах лимита проходят без ограничения")
    void check_requestsWithinLimit_passThrough() {
        // given / when / then
        for (int i = 1; i <= 3; i++) {
            assertThat(service.check("key", 3, Duration.ofMinutes(1)).allowed()).as("запрос %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("Четвёртый запрос блокируется, retry-after в пределах окна")
    void check_limitExceeded_blocksWithRetryAfter() {
        // given
        service.check("key", 3, Duration.ofMinutes(1));
        service.check("key", 3, Duration.ofMinutes(1));
        service.check("key", 3, Duration.ofMinutes(1));

        // when
        RateLimitService.Decision decision = service.check("key", 3, Duration.ofMinutes(1));

        // then
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("Лимит по одному ключу не влияет на другой")
    void check_differentKeys_areIndependent() {
        // given
        for (int i = 1; i <= 3; i++) {
            service.check("ip1", 3, Duration.ofMinutes(1));
        }

        // when / then
        assertThat(service.check("ip1", 3, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(service.check("ip2", 3, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    @DisplayName("Один IP с разными путями запросов имеет отдельные лимиты")
    void check_differentRequestPaths_keysAreIndependent() {
        // given
        for (int i = 1; i <= 2; i++) {
            service.check("ip|POST /api/v1/auth/login", 2, Duration.ofMinutes(1));
        }

        // when / then
        assertThat(service.check("ip|POST /api/v1/auth/login", 2, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(service.check("ip|POST /api/v1/auth/register", 2, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    @DisplayName("После истечения скользящего окна запрос снова разрешён")
    void check_windowExpired_allowsRequestAgain() {
        // given
        Duration window = Duration.ofMillis(30);
        assertThat(service.check("key", 1, window).allowed()).isTrue();
        assertThat(service.check("key", 1, window).allowed()).isFalse();

        // when / then
        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertThat(service.check("key", 1, window).allowed()).isTrue());
    }

    @Test
    @DisplayName("После reset исчерпанный лимит начинает проходить заново")
    void reset_afterExhaustedLimit_allowsRequestsAgain() {
        // given
        for (int i = 1; i <= 2; i++) {
            service.check("key", 2, Duration.ofMinutes(1));
        }
        assertThat(service.check("key", 2, Duration.ofMinutes(1)).allowed()).isFalse();

        // when
        service.reset();

        // then
        assertThat(service.check("key", 2, Duration.ofMinutes(1)).allowed()).isTrue();
    }
}

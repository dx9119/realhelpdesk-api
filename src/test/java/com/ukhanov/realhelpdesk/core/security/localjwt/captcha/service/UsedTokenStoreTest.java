package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Одноразовость решений капчи: повтор того же id отклоняется")
class UsedTokenStoreTest {

    @Test
    @DisplayName("Первое использование проходит, повтор — нет; разные ключи независимы")
    void firstUse_sameKeyTwice_acceptsOnlyFirst() {

        // given
        // when
        UsedTokenStore store = new UsedTokenStore(300);

        boolean firstTicket = store.firstUse("t:abc");
        boolean replayTicket = store.firstUse("t:abc");
        boolean otherTicket = store.firstUse("t:def");
        boolean firstChallenge = store.firstUse("c:xyz");
        boolean replayChallenge = store.firstUse("c:xyz");

        // then
        assertThat(firstTicket).isTrue();
        assertThat(replayTicket).isFalse();
        assertThat(otherTicket).isTrue();
        assertThat(firstChallenge).isTrue();
        assertThat(replayChallenge).isFalse();
    }

    @Test
    @DisplayName("Запись вытесняется после TTL — до истечения JWT всё равно отклоняет подпись/exp")
    void firstUse_afterTtlExpiry_acceptsKeyAgain() {

        // given
        // when
        UsedTokenStore store = new UsedTokenStore(1);
        // then
        assertThat(store.firstUse("t:once")).isTrue();

        // when / then — ожидание по условию вместо фиксированной паузы: Caffeine чистит запись лениво
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(store.firstUse("t:once")).isTrue());
    }

    @Test
    @DisplayName("TTL кэша из свойств: max(challenge, token) + запас — не меньше срока жизни challenge")
    void constructor_ttlProperties_exceedChallengeTtl() throws Exception {

        // given
        // when
        CaptchaProperties properties = new CaptchaProperties();
        properties.setChallengeTtlSeconds(600);
        properties.setTokenTtlSeconds(300);
        properties.setPowDifficulty(18);
        properties.setMinFillSeconds(0);

        UsedTokenStore store = new UsedTokenStore(properties);
        var field = UsedTokenStore.class.getDeclaredField("used");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Cache<String, Boolean> cache = (Cache<String, Boolean>) field.get(store);

        var expirePolicy = cache.policy().expireAfterWrite().orElseThrow();
        // then
        assertThat(expirePolicy.getExpiresAfter()).isEqualTo(Duration.ofSeconds(600 + UsedTokenStore.EXPIRE_MARGIN_SECONDS));
    }
}

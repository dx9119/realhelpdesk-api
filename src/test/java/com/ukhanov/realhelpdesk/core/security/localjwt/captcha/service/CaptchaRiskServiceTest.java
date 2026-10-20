package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaRiskService.Risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Риск-движок капчи: провалы по IP (мягкий риск) и по подсети (эмодзи-эскалация)")
class CaptchaRiskServiceTest {

    private CaptchaRiskService riskService;

    @BeforeEach
    void setUp() {
        riskService = new CaptchaRiskService(properties());
    }

    @Test
    @DisplayName("Провалы одного IP дают мягкий риск ELEVATED по достижении порога, не EMOJI")
    void recordFailure_thresholdReached_returnsElevated() {

        // given
        // when
        riskService.recordFailure("1.2.3.4");
        riskService.recordFailure("1.2.3.4");
        // then
        assertThat(riskService.riskLevel("1.2.3.4")).isEqualTo(Risk.NONE);

        riskService.recordFailure("1.2.3.4");

        assertThat(riskService.riskLevel("1.2.3.4")).isEqualTo(Risk.ELEVATED);
    }

    @Test
    @DisplayName("Много провалов одного IP (NAT-сеть) всё равно не даёт EMOJI")
    void recordFailure_fiftyFailuresFromOneIp_staysElevated() {

        // given
        // when
        for (int i = 0; i < 50; i++) {
            riskService.recordFailure("1.2.3.4");
        }

        // then
        assertThat(riskService.riskLevel("1.2.3.4")).as("один внешний адрес NAT не срабатывает как распределённая атака")
                .isEqualTo(Risk.ELEVATED);
    }

    @Test
    @DisplayName("Провалы разных IP одной подсети дают EMOJI по достижении порога")
    void recordFailure_twoIpsInSubnet_returnsEmojiRisk() {

        // given
        // when
        riskService.recordFailure("1.2.3.4");
        // then
        assertThat(riskService.riskLevel("1.2.3.5")).isNotEqualTo(Risk.EMOJI);

        riskService.recordFailure("1.2.3.5");

        assertThat(riskService.riskLevel("1.2.3.4")).as("в подсети /24 уже два разных провалившихся IP").isEqualTo(Risk.EMOJI);
        assertThat(riskService.riskLevel("1.2.3.99")).as("эскалация считается по подсети, не по конкретному IP").isEqualTo(Risk.EMOJI);
    }

    @Test
    @DisplayName("Провалы в разных подсетях не суммируются")
    void recordFailure_failuresInDifferentSubnets_doNotEscalate() {

        // given
        // when
        riskService.recordFailure("1.2.3.4");
        riskService.recordFailure("5.6.7.8");

        // then
        assertThat(riskService.riskLevel("1.2.3.4")).isNotEqualTo(Risk.EMOJI);
        assertThat(riskService.riskLevel("5.6.7.8")).isNotEqualTo(Risk.EMOJI);
    }

    @Test
    @DisplayName("Успех сбрасывает IP и его вклад в подсеть")
    void reset_afterSubnetEscalation_clearsEmojiRisk() {

        // given
        // when
        riskService.recordFailure("1.2.3.4");
        riskService.recordFailure("1.2.3.5");
        riskService.recordFailure("1.2.3.4");
        // then
        assertThat(riskService.riskLevel("1.2.3.4")).isEqualTo(Risk.EMOJI);

        riskService.reset("1.2.3.5");

        assertThat(riskService.riskLevel("1.2.3.4")).as("в подсети остался один провалившийся IP").isNotEqualTo(Risk.EMOJI);
    }

    @Test
    @DisplayName("Неизвестный IP и null не рискованные; null/пустое в recordFailure/reset — no-op")
    void riskLevel_unknownOrBlankIp_returnsNone() {

        // given
        // when
        riskService.recordFailure(null);
        riskService.recordFailure("   ");
        riskService.reset(null);
        riskService.reset("");

        // then
        assertThat(riskService.riskLevel(null)).isEqualTo(Risk.NONE);
        assertThat(riskService.riskLevel("")).isEqualTo(Risk.NONE);
        assertThat(riskService.riskLevel("9.9.9.9")).isEqualTo(Risk.NONE);
    }

    @Test
    @DisplayName("Невалидные конфигурационные значения — старт падает с понятной ошибкой")
    void constructor_invalidProperties_throwsIllegalStateException() {

        // given / when / then
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskFailureThreshold(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-failure-threshold");
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskFailureWindowSeconds(5))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-failure-window-seconds");
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskElevatedDifficultyBonus(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-elevated-difficulty-bonus");
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskSubnetOffenderThreshold(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-subnet-offender-threshold");
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskSubnetPrefixV4(40))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-subnet-prefix-v4");
        assertThatThrownBy(() -> new CaptchaRiskService(properties(builder -> builder.riskSubnetPrefixV6(200))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk-subnet-prefix-v6");
    }

    private CaptchaProperties properties() {
        return properties(builder -> {
        });
    }

    private CaptchaProperties properties(java.util.function.Consumer<Builder> customizer) {
        Builder builder = new Builder();
        customizer.accept(builder);
        CaptchaProperties properties = new CaptchaProperties();
        properties.setRiskFailureThreshold(builder.threshold);
        properties.setRiskFailureWindowSeconds(builder.window);
        properties.setRiskElevatedDifficultyBonus(builder.bonus);
        properties.setRiskSubnetOffenderThreshold(builder.offenderThreshold);
        properties.setRiskSubnetPrefixV4(builder.prefixV4);
        properties.setRiskSubnetPrefixV6(builder.prefixV6);
        return properties;
    }

    /** Мутабельные значения по умолчанию для тестов валидации конструктора. */
    private static final class Builder {
        private Integer threshold = 3;
        private Integer window = 600;
        private Integer bonus = 3;
        private Integer offenderThreshold = 2;
        private Integer prefixV4 = 24;
        private Integer prefixV6 = 64;

        void riskFailureThreshold(Integer value) {
            this.threshold = value;
        }

        void riskFailureWindowSeconds(Integer value) {
            this.window = value;
        }

        void riskElevatedDifficultyBonus(Integer value) {
            this.bonus = value;
        }

        void riskSubnetOffenderThreshold(Integer value) {
            this.offenderThreshold = value;
        }

        void riskSubnetPrefixV4(Integer value) {
            this.prefixV4 = value;
        }

        void riskSubnetPrefixV6(Integer value) {
            this.prefixV6 = value;
        }
    }
}

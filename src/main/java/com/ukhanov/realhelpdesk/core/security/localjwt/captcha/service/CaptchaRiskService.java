package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ukhanov.realhelpdesk.core.config.ConfigValidator;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;

/**
 * Локальный риск-движок капчи: считает провалы проверок по IP и по подсети за скользящее окно. Внешние анти-бот
 * провайдеры не используются — только значения из конфигурации {@code captcha.*}.
 *
 * <p>
 * Два независимых сигнала дают разный отклик:
 * </p>
 * <ul>
 * <li><b>Провалы одного IP</b> ({@code captcha.risk-failure-threshold}) — мягкий риск {@link Risk#ELEVATED}:
 * {@link CaptchaService} повышает сложность PoW, но не выдаёт эмодзи-задание. Домашняя или NAT-сеть с одним внешним
 * адресом на множество клиентов может набрать такой порог из-за провалов отдельных пользователей, и мягкий отклик не
 * наказывает всех.</li>
 * <li><b>Провалы разных IP одной подсети</b> ({@code captcha.risk-subnet-offender-threshold}, префиксы
 * {@code captcha.risk-subnet-prefix-v4|v6}) — признак распределённой атаки {@link Risk#EMOJI}: выдаётся эмодзи-задание.
 * Один внешний адрес NAT даёт в подсети один offender и этот порог не задевает.</li>
 * </ul>
 *
 * <p>
 * Оба счётчика живут в Caffeine с {@code expireAfterWrite} (окно {@code captcha.risk-failure-window-seconds}) и
 * сбрасываются для IP после успешной проверки ({@link #reset(String)}). Привязка к IP не жёсткая: адрес — только сигнал
 * риска.
 * </p>
 */
@Service
public class CaptchaRiskService {

    /** Отклик на риск: обычная проверка, усложнённый PoW или эмодзи-эскалация. */
    public enum Risk {
        NONE, ELEVATED, EMOJI
    }

    private static final long MAX_TRACKED_IPS = 50_000;
    private static final long MAX_TRACKED_SUBNETS = 10_000;

    private static final int MAX_RISK_THRESHOLD = 1_000;
    private static final int MIN_WINDOW_SECONDS = 10;
    private static final int MAX_WINDOW_SECONDS = 86_400;
    private static final int MAX_DIFFICULTY_BONUS = 26;
    private static final int MAX_OFFENDER_THRESHOLD = 1_000;
    private static final int MAX_IPV4_PREFIX = 32;
    private static final int MAX_IPV6_PREFIX = 128;
    private static final int IPV4_ADDRESS_BYTES = 4;
    private static final int BITS_PER_BYTE = 8;

    private final int failureThreshold;
    private final int subnetOffenderThreshold;
    private final int subnetPrefixV4;
    private final int subnetPrefixV6;

    private final Cache<String, Integer> failures;
    private final Cache<String, Set<String>> subnetOffenders;

    public CaptchaRiskService(CaptchaProperties properties) {
        Integer threshold = properties.getRiskFailureThreshold();
        Integer windowSeconds = properties.getRiskFailureWindowSeconds();
        Integer bonus = properties.getRiskElevatedDifficultyBonus();
        Integer offenderThreshold = properties.getRiskSubnetOffenderThreshold();
        Integer prefixV4 = properties.getRiskSubnetPrefixV4();
        Integer prefixV6 = properties.getRiskSubnetPrefixV6();
        this.failureThreshold = ConfigValidator.requireInRange(threshold, 1, MAX_RISK_THRESHOLD,
                "captcha.risk-failure-threshold");
        this.subnetOffenderThreshold = ConfigValidator.requireInRange(offenderThreshold, 1, MAX_OFFENDER_THRESHOLD,
                "captcha.risk-subnet-offender-threshold");
        this.subnetPrefixV4 = ConfigValidator.requireInRange(prefixV4, 1, MAX_IPV4_PREFIX,
                "captcha.risk-subnet-prefix-v4");
        this.subnetPrefixV6 = ConfigValidator.requireInRange(prefixV6, 1, MAX_IPV6_PREFIX,
                "captcha.risk-subnet-prefix-v6");
        int window = ConfigValidator.requireInRange(windowSeconds, MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS,
                "captcha.risk-failure-window-seconds");
        ConfigValidator.requireInRange(bonus, 1, MAX_DIFFICULTY_BONUS, "captcha.risk-elevated-difficulty-bonus");

        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(window, TimeUnit.SECONDS)
                .maximumSize(MAX_TRACKED_IPS)
                .build();
        this.subnetOffenders = Caffeine.newBuilder()
                .expireAfterWrite(window, TimeUnit.SECONDS)
                .maximumSize(MAX_TRACKED_SUBNETS)
                .<String, Set<String>>build();
    }

    /** Учтён очередной провал капчи с этого IP: растёт счётчик IP и множество провалившихся IP его подсети. */
    public void recordFailure(String clientIp) {
        if (isBlank(clientIp)) {
            return;
        }
        failures.asMap().merge(clientIp, 1, Integer::sum);
        String subnet = subnetKey(clientIp);
        if (subnet != null) {
            subnetOffenders.asMap().computeIfAbsent(subnet, key -> ConcurrentHashMap.newKeySet()).add(clientIp);
        }
    }

    /** Уровень риска IP: эмодзи при распределённой атаке по подсети, усложнённый PoW при провалах одного IP. */
    public Risk riskLevel(String clientIp) {
        if (isBlank(clientIp)) {
            return Risk.NONE;
        }
        if (isSubnetDistributed(clientIp)) {
            return Risk.EMOJI;
        }
        Integer count = failures.getIfPresent(clientIp);
        return count != null && count >= failureThreshold ? Risk.ELEVATED : Risk.NONE;
    }

    /** Сброс сигналов IP после успешной проверки капчи. */
    public void reset(String clientIp) {
        if (isBlank(clientIp)) {
            return;
        }
        failures.invalidate(clientIp);
        String subnet = subnetKey(clientIp);
        if (subnet != null) {
            Set<String> offenders = subnetOffenders.getIfPresent(subnet);
            if (offenders != null) {
                offenders.remove(clientIp);
            }
        }
    }

    private boolean isSubnetDistributed(String clientIp) {
        String subnet = subnetKey(clientIp);
        if (subnet == null) {
            return false;
        }
        Set<String> offenders = subnetOffenders.getIfPresent(subnet);
        return offenders != null && offenders.size() >= subnetOffenderThreshold;
    }

    /**
     * Ключ подсети клиента: адрес с обнулёнными битами после префикса ({@code captcha.risk-subnet-prefix-v4|v6}) и
     * длиной префикса. {@code null} — адрес не разобран, сигнал подсети не учитывается.
     */
    private String subnetKey(String clientIp) {
        if (!looksLikeIp(clientIp)) {
            return null;
        }
        try {
            byte[] bytes = InetAddress.getByName(clientIp).getAddress();
            int prefix = bytes.length == IPV4_ADDRESS_BYTES ? subnetPrefixV4 : subnetPrefixV6;
            int bits = Math.min(prefix, bytes.length * BITS_PER_BYTE);
            byte[] masked = bytes.clone();
            for (int bit = bits; bit < bytes.length * BITS_PER_BYTE; bit++) {
                masked[bit / BITS_PER_BYTE] &= (byte) ~(1 << (BITS_PER_BYTE - 1 - (bit % BITS_PER_BYTE)));
            }
            return InetAddress.getByAddress(masked).getHostAddress() + "/" + bits;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Числовой IPv4 или IPv6 с двоеточием: имена хостов не разбираем, чтобы не запускать DNS. */
    private static boolean looksLikeIp(String value) {
        if (value.indexOf(':') >= 0) {
            return true;
        }
        return value.matches("[0-9.]+");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

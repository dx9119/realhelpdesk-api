package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Настройка капчи из конфигурации: включение, HMAC-секрет подписи PoW-токенов, сложность Proof-of-Work и сроки (в
 * секундах). Только биндинг ключей {@code captcha.*} — валидация значений и сборка ключа подписи живут в
 * {@link CaptchaConfig} и {@code CaptchaTokenService}, их легко тестировать, подставляя готовый экземпляр.
 */
@Component
@ConfigurationProperties(prefix = "captcha")
@Getter
@Setter
public class CaptchaProperties {

    /** Капча включена (true) или выключена (false): выключена — проверка токена на регистрации пропускается. */
    private Boolean enabled;

    /** HMAC-секрет подписи challenge и токенов (минимум 32 символа). */
    private String hmacSecret;

    /** Сложность PoW — число ведущих нулевых бит SHA-256 (1..26). */
    private Integer powDifficulty;

    /** Срок жизни challenge от выдачи до решения, секунды. */
    private Integer challengeTtlSeconds;

    /** Срок жизни выданного токена от solve до отправки формы, секунды. */
    private Integer tokenTtlSeconds;

    /** Минимальное время от выдачи challenge до отправки формы, секунды (0 — проверка отключена). */
    private Integer minFillSeconds;

    /**
     * Эскалация на эмодзи-уровень включена (true): рискованные IP получают эмодзи-задание вместо PoW (kill-switch —
     * false).
     */
    private Boolean emojiEnabled;

    /**
     * Сколько провалов капчи у одного IP за окно считаются мягким риском: PoW усложняется, но эмодзи ещё не выдаётся
     * (1..1000).
     */
    private Integer riskFailureThreshold;

    /**
     * Окно (секунды) учитываемых провалов капчи на IP; счётчик сбрасывается по истечении окна или после успешной
     * проверки (10..86400).
     */
    private Integer riskFailureWindowSeconds;

    /** На сколько бит добавляется к базовой сложности PoW для IP с мягким риском (1..26). */
    private Integer riskElevatedDifficultyBonus;

    /**
     * Сколько разных IP в одной подсети должны провалиться за окно, чтобы сработала эмодзи-эскалация: признак
     * распределённой атаки (1..1000).
     */
    private Integer riskSubnetOffenderThreshold;

    /** Длина префикса подсети IPv4 для группировки провалов по подсети (1..32). */
    private Integer riskSubnetPrefixV4;

    /** Длина префикса подсети IPv6 для группировки провалов по подсети (1..128). */
    private Integer riskSubnetPrefixV6;
}

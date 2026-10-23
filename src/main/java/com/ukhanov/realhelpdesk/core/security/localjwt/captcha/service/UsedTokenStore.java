package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.config.CaptchaProperties;

/**
 * Одноразовость решений капчи в памяти (одна инстанция приложения): ключ — предъявленный id ({@code "t:"+jti}
 * captcha-токена или {@code "c:"+cid} challenge), значение — факт первого использования. {@code firstUse} атомарен
 * через {@code asMap().putIfAbsent} — два параллельных запроса с одним токеном проходят только один.
 *
 * <p>
 * Ограничен размером и переживает токены своим TTL: {@code max(challenge, token) TTL + запас} покрывает оба типа ключей
 * — после этого истечения сам JWT всё равно просрочен ({@code exp}) и отклоняется подписью, так что выпавшая запись не
 * открывает replay.
 * </p>
 */
@Component
public class UsedTokenStore {

    static final int MAX_ENTRIES = 50_000;
    static final long EXPIRE_MARGIN_SECONDS = 60;

    private final Cache<String, Boolean> used;

    @Autowired
    public UsedTokenStore(CaptchaProperties properties) {
        this(Math.max(properties.getChallengeTtlSeconds(), properties.getTokenTtlSeconds()) + EXPIRE_MARGIN_SECONDS);
    }

    /** Конструктор для тестов: явный срок жизни записей в секундах. */
    UsedTokenStore(long expireSeconds) {
        this.used = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Первое использование id? true — проходит, false — повтор (replay).
     */
    public boolean firstUse(String key) {
        return used.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }
}

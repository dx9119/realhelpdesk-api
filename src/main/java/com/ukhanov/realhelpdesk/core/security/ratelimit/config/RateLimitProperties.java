package com.ukhanov.realhelpdesk.core.security.ratelimit.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Пороги рейт-лимитов из конфига: ключи аннотации {@code @RateLimit(key = ...)} и лимит писем восстановления пароля.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit")
@Getter
@Setter
public class RateLimitProperties {

    /** Пороги по ключам: auth-login, captcha, email-code и т.д. */
    private Map<String, Limit> limits = new LinkedHashMap<>();

    /**
     * Порог лимита: число запросов за окно windowSeconds.
     */
    public record Limit(int requests, int windowSeconds) {

        public Limit {
            if (requests < 1) {
                throw new IllegalArgumentException("ratelimit: requests должен быть больше 0, получено " + requests);
            }
            if (windowSeconds < 1) {
                throw new IllegalArgumentException(
                        "ratelimit: windowSeconds должен быть больше 0, получено " + windowSeconds);
            }
        }
    }

    /**
     * Возвращает порог по ключу или падает с понятной ошибкой, если лимит не описан в конфиге.
     */
    public Limit require(String key) {
        Limit limit = limits.get(key);
        if (limit == null) {
            throw new IllegalStateException("Rate limit не задан в конфиге: ratelimit.limits." + key);
        }
        return limit;
    }
}

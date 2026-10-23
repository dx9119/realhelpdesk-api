package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Настройки JWT из конфигурации: подпись, адреса, сроки жизни и cookie. Только биндинг ключей {@code jwt.*} — валидация
 * значений и вычисление ключа подписи живут в {@link JwtConfig}, их легко тестировать, подставляя готовый экземпляр.
 *
 * <p>
 * Схема ключей: {@code application-domains.properties} (issuer/audience) и {@code application.properties} (секрет,
 * сроки, cookie).
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** Base64-секрет подписи HS256: минимум 32 байта. */
    private String secretForGenJwt;

    /** Домен выпуска токенов: пишется в claim {@code iss} и сверяется при валидации. */
    private String issuer;

    /** Домены-аудитории токенов: claim {@code aud}, список через запятую. */
    private Set<String> audience = new LinkedHashSet<>();

    /** Срок жизни access-токена в секундах. */
    private Integer accessTokenExpirationSeconds;

    /** Срок жизни refresh-токена в секундах. */
    private Integer refreshTokenExpirationSeconds;

    /**
     * Сколько секунд хранить отозванные строки {@code jwt_tokens} до удаления фоновой задачей. Отзыв токена меняет
     * только статус, поэтому строки копятся; активные строки удаляются отдельно по истечении срока жизни токена, этот
     * срок их не касается.
     */
    private Integer revokedTokenRetentionSeconds;

    /** Cron-расписание задачи ротации refresh-токенов (6 полей, с секундами). */
    private String tokenCleanupCron;

    /** Стартовый и минимальный размер батча удаления при ротации refresh-токенов. */
    private Integer tokenCleanupBatchMinSize;

    /** Верхняя граница адаптивного размера батча удаления при ротации refresh-токенов. */
    private Integer tokenCleanupBatchMaxSize;

    /** Целевое время одного батча (мс): быстрее — размер растёт, дольше — уменьшается. */
    private Integer tokenCleanupTargetBatchMs;

    /** Пауза между батчами удаления (мс): разносит нагрузку на БД, убирая пики. */
    private Integer tokenCleanupBatchPauseMs;

    /**
     * Настройки auth-cookie. Ключи вложены ({@code jwt.cookie.same-site}), поэтому и класс вложен: плоское поле
     * {@code cookieSameSite} из двухэлементного ключа не биндится — см. JwtPropertiesTest.
     */
    private Cookie cookie = new Cookie();

    /** Cookie auth-токенов. */
    @Getter
    @Setter
    public static class Cookie {

        /**
         * SameSite для auth-cookie: каким элементом {@link SameSite} заполнится поле, решает биндинг по ключу
         * {@code jwt.cookie.same-site} — список допустимых значений есть и в enum, и в комментариях у ключа в
         * properties.
         */
        private SameSite sameSite = SameSite.NONE;

        /**
         * Домен auth-cookie: {@code null} (пусто в конфигурации) — host-only, cookie уходит только на свой хост;
         * {@code .example.com} — общий для поддоменов, без него файлы на отдельном домене ({@code static.base-url})
         * браузеру отправить нельзя.
         */
        private String domain;

        /**
         * Пробелы и пустое значение нормализуются в {@code null}: пустой атрибут Domain в Set-Cookie браузер
         * отбрасывает сам.
         */
        public void setDomain(String domain) {
            if (domain == null) {
                this.domain = null;
                return;
            }
            String trimmed = domain.trim();
            this.domain = trimmed.isEmpty() ? null : trimmed;
        }
    }
}

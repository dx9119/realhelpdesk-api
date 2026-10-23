package com.ukhanov.realhelpdesk.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Настройки раздачи статического контента (файлы вложений): origin, с которого клиент забирает байты (домен или ip:port
 * перед приложением — reverse-proxy, дальше CDN), и политика кэширования этих байтов.
 *
 * <p>
 * Из {@code static.base-url} маппер вложений собирает {@code downloadUrl} в ответах API, чтобы клиент ходил за байтами
 * на этот адрес. Пустое значение — ссылки относительные, файлы отдаёт само API на своём хосте, как до появления
 * настройки. Работа через чужой домен требует ещё {@code jwt.cookie.domain} — без общего домена cookie auth-токена на
 * этот origin не уходит.
 * </p>
 *
 * <p>
 * {@code static.cache-control} уходит в заголовок {@code Cache-Control} ответа на скачивание как есть: дефолт
 * {@code private, no-store} — ничто не кэширует; значение для CDN (обычно с {@code public}) имеет смысл только вместе с
 * доступом, не завязанным на cookie, иначе закрытый файл может отдать кэш.
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "static")
public class StaticProperties {

    /** Закрытая политика по умолчанию — та же, что была до появления настройки: браузер и кэши файл не сохраняют. */
    public static final String DEFAULT_CACHE_CONTROL = "private, no-store";

    /**
     * Публичный origin раздачи файлов без хвостового слэша; {@code null} — пусто или не задано, ссылки относительные.
     */
    private String baseUrl;

    /** Cache-Control для отдачи файлов; пусто или не задано — {@link #DEFAULT_CACHE_CONTROL}. */
    private String cacheControl = DEFAULT_CACHE_CONTROL;

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Убирает пробелы и хвостовые слэши: {@code https://cdn.example.com/} и {@code cdn.example.com} склеиваются с путём
     * без «//».
     */
    public void setBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            this.baseUrl = null;
            return;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized.isEmpty() ? null : normalized;
    }

    public String getCacheControl() {
        return cacheControl;
    }

    /**
     * Пустое значение нормализуется в дефолт: случайно обнуленная переменная не ослабляет политику, а оставляет
     * закрытую.
     */
    public void setCacheControl(String cacheControl) {
        if (cacheControl == null) {
            this.cacheControl = DEFAULT_CACHE_CONTROL;
            return;
        }
        String trimmed = cacheControl.trim();
        this.cacheControl = trimmed.isEmpty() ? DEFAULT_CACHE_CONTROL : trimmed;
    }
}

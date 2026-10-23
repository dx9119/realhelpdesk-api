package com.ukhanov.realhelpdesk.core.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * CORS-политика из конфигурации: origin'ы фронта, которым разрешён доступ к API.
 *
 * <p>
 * Значение — список через запятую ({@code CORS_ALLOWED_ORIGINS}), каждый элемент — полный origin вида
 * {@code scheme://host[:port]}: браузер сравнивает origin запроса со списком буквально, поэтому {@code example.com} и
 * {@code https://example.com} — разные значения.
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "cors")
@Getter
public class CorsProperties {

    /** Разрешённые origin'ы фронта; пустой список = доступ не пройдёт ни одному origin, включая фронт. */
    private List<String> allowedOrigins = new ArrayList<>();

    /** Убирает пробелы вокруг origin и пустые элементы: браузер сравнивает origin запроса со списком буквально. */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        if (allowedOrigins == null) {
            this.allowedOrigins = new ArrayList<>();
            return;
        }
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }
}

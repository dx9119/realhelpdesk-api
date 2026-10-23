package com.ukhanov.realhelpdesk.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Учётная запись HTTP Basic для скрейпа {@code /actuator/prometheus} на управляющем порту.
 *
 * <p>
 * Скрейп (alloy → Mimir) не ходит за метриками с пользовательским JWT, поэтому метрики закрыты отдельной сервисной
 * учёткой: имя — {@code METRICS_USER} (infrastructure/.env), пароль — {@code METRICS_PASSWORD} (секрет из
 * infrastructure/.env, в репозиторий не попадает). Пустой пароль означает, что никто не пройдёт аутентификацию — скрейп
 * закрыт, а не открыт.
 * </p>
 *
 * <p>
 * Переключатель {@code observability.prometheus.auth.enabled=false} снимает проверку пароля: допустимо только когда
 * управляющий порт закрыт сетью и до него не добирается посторонний.
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "observability.prometheus.auth")
@Getter
@Setter
public class PrometheusScrapeProperties {

    /**
     * Проверять Basic-пароль на /actuator/prometheus. false — отдать метрики без аутентификации (только за закрытым
     * портом).
     */
    private boolean enabled = true;

    /** Имя сервисного пользователя скрейпа (METRICS_USER). */
    private String username = "";

    /** Пароль сервисного пользователя скрейпа (METRICS_PASSWORD, секрет из infrastructure/.env). */
    private String password = "";

    /** Учётные данные заполнены: без них аутентификация пройти не может — остаётся закрытый скрейп. */
    public boolean hasCredentials() {
        return !username.isBlank() && !password.isBlank();
    }
}

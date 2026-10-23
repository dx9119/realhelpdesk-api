package com.ukhanov.realhelpdesk.core.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Счётчик попыток аутентификации {@code auth.attempts} с тегами {@code type} и {@code result}.
 *
 * <p>
 * Значения тегов — конечные списки: type = login/refresh/captcha/password_reset, result = success/failure/locked.
 * Email, имя пользователя и текст ошибки в теги не попадают — они высококардинальные и могут содержать ПДн.
 * </p>
 */
@Component
public class AuthMetrics {

    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_REFRESH = "refresh";
    public static final String TYPE_CAPTCHA = "captcha";
    public static final String TYPE_PASSWORD_RESET = "password_reset";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
    public static final String RESULT_LOCKED = "locked";

    private static final String METRIC_NAME = "auth.attempts";

    private static final String DESCRIPTION = "Попытки аутентификации по типу и результату";

    private final MeterRegistry meterRegistry;

    public AuthMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String type, String result) {
        Counter.builder(METRIC_NAME)
                .description(DESCRIPTION)
                .tag("type", type)
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}

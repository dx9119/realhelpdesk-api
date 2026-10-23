package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.observability;

import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Метрики капчи: выдачи challenge и итоги проверки с тегами type и reason — по ним видно, доля ли эмодзи-эскалации
 * (type=emoji), отсекает ли PoW ботов (pow_failed), ловит ли replay (replayed) и не включён ли флаг fail-open (успехи
 * при выключенной капче — отдельная метрика не нужна, смотрится в связке с captcha.challenge.issued).
 */
@Component
public class CaptchaMetrics {

    /** PoW-уровень: challenge с солью/сложностью или токен, решённый PoW. */
    public static final String TYPE_POW = "pow";

    /** Эмодзи-уровень: задание с глифами (эскалация по риску) или токен, решённый выбором ячеек. */
    public static final String TYPE_EMOJI = "emoji";

    /** Тип неизвестен: honeypot-отказ или разбор токена не удался до чтения уровня/источника. */
    public static final String TYPE_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public CaptchaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Выдан challenge (POST /challenge); type — уровень задания: pow или emoji. */
    public void challengeIssued(String type) {
        Counter.builder("captcha.challenge.issued")
                .description("Сколько challenge выдано клиентам")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** Итог проверки на бизнес-операции или решении challenge: reason=ok для успеха, type — уровень доказательства. */
    public void verification(boolean success, CaptchaReason reason, String type) {
        Counter.builder("captcha.verification")
                .description("Результаты проверки капчи")
                .tag("type", type)
                .tag("result", success ? "success" : "failure")
                .tag("reason", reason.code())
                .register(registry)
                .increment();
    }
}

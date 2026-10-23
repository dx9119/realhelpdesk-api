package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception;

/**
 * Причина провала капчи: код в problem+json ({@code code}) и тег {@code reason} метрики {@code captcha.verification}.
 * Список конечный — без пользовательских данных, чтобы не раздувать кардинальность метрик.
 */
public enum CaptchaReason {

    /** Проверка пройдена. */
    OK("ok"),
    /** Токен не передан (нет поля/заголовка). */
    MISSING_TOKEN("missing_token"),
    /** Подпись не сходится, битый формат или не тот тип токена на уровне формата. */
    INVALID("invalid"),
    /** Срок жизни токена или challenge истёк. */
    EXPIRED("expired"),
    /** Токен или challenge уже использованы. */
    REPLAYED("replayed"),
    /** Предъявлен не тот тип токена (challenge вместо captcha-токена и наоборот). */
    WRONG_TYPE("wrong_type"),
    /** Токен выдан на другое действие (register вместо email-code). */
    WRONG_ACTION("wrong_action"),
    /** Форма отправлена раньше минимального времени заполнения. */
    TOO_FAST("too_fast"),
    /** Proof-of-Work не решён: хэш не набрал нужное число нулевых бит. */
    POW_FAILED("pow_failed"),
    /** Заполнено honeypot-поле, невидимое человеку. */
    HONEYPOT("honeypot"),
    /** Неверно решено эмодзи-задание: не тот набор ячеек или пустой ответ. */
    WRONG_ANSWER("wrong_answer");

    private final String code;

    CaptchaReason(String code) {
        this.code = code;
    }

    /** Значение в API и в теге метрики. */
    public String code() {
        return code;
    }
}

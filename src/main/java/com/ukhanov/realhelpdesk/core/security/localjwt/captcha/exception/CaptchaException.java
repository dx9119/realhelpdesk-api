package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception;

import org.springframework.http.HttpStatus;

import com.ukhanov.realhelpdesk.core.exception.ApiException;

import lombok.Getter;

/**
 * Провал капчи: HTTP 400 с problem+json. {@link #getReason()} — машинная причина ({@code code} в ответе и тег метрики);
 * у старых конструкторов без причины она считается {@link CaptchaReason#INVALID}.
 */
@Getter
public class CaptchaException extends ApiException {

    private final CaptchaReason reason;

    public CaptchaException(String message) {
        this(message, CaptchaReason.INVALID);
    }

    public CaptchaException(String message, Throwable cause) {
        this(message, CaptchaReason.INVALID, HttpStatus.BAD_REQUEST, cause);
    }

    public CaptchaException(String message, CaptchaReason reason) {
        this(message, reason, HttpStatus.BAD_REQUEST, null);
    }

    public CaptchaException(String message, CaptchaReason reason, Throwable cause) {
        this(message, reason, HttpStatus.BAD_REQUEST, cause);
    }

    public CaptchaException(String message, HttpStatus status) {
        this(message, CaptchaReason.INVALID, status, null);
    }

    public CaptchaException(String message, HttpStatus status, Throwable cause) {
        this(message, CaptchaReason.INVALID, status, cause);
    }

    private CaptchaException(String message, CaptchaReason reason, HttpStatus status, Throwable cause) {
        super(message, status, cause);
        this.reason = reason;
    }
}

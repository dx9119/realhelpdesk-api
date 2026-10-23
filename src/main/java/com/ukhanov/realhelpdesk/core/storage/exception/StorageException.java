package com.ukhanov.realhelpdesk.core.storage.exception;

import org.springframework.http.HttpStatus;

import com.ukhanov.realhelpdesk.core.exception.ApiRuntimeException;

/**
 * Сбой хранилища файлов (MinIO недоступен, бакет не создан, объект пропал). Невы-checked: ошибка инфраструктуры, а не
 * контракта вызывающего кода; ловится один раз в GlobalExceptionHandler и уходит клиенту как problem+json.
 */
public class StorageException extends ApiRuntimeException {

    /** Хранилище недоступно или отвечает ошибкой — 503, повтор запроса имеет смысл. */
    public StorageException(String message) {
        this(message, HttpStatus.SERVICE_UNAVAILABLE, null);
    }

    public StorageException(String message, Throwable cause) {
        this(message, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    /** Объект в бакете отсутствует — не баг вызывающего кода, ответ 404 без паники в логах. */
    public static StorageException notFound(String message) {
        return new StorageException(message, HttpStatus.NOT_FOUND, null);
    }

    private StorageException(String message, HttpStatus status, Throwable cause) {
        super(message, status, cause);
    }
}

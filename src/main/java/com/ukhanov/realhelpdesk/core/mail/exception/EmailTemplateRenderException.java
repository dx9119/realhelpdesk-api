package com.ukhanov.realhelpdesk.core.mail.exception;

/**
 * Ошибка рендера HTML-шаблона письма.
 *
 * <p>
 * Сообщение исключения не содержит содержимого модели и HTML-тела письма (в письмах есть токены, коды, причины передачи
 * портала) — в лог уходит только имя шаблона, в клиентский ответ — текст без деталей.
 * </p>
 */
public class EmailTemplateRenderException extends RuntimeException {

    public EmailTemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}

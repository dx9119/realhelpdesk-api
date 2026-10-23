package com.ukhanov.realhelpdesk.core.exception;

import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Инфраструктурные ошибки HTTP/почты/multipart: выделены из {@link GlobalExceptionHandler}, чтобы доменные и
 * инфраструктурные ответы не разрастались в один класс.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class InfrastructureExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ApiResponse(responseCode = "400", description = "Отсутствует обязательный query-параметр",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        logger.warn("Отсутствует обязательный параметр '{}'", ex.getParameterName());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST,
                "Отсутствует обязательный параметр '" + ex.getParameterName() + "'",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ApiResponse(responseCode = "413", description = "Файл превышает допустимый размер",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        logger.warn("Загружаемый файл превышает лимит: {}", ex.getMessage());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.CONTENT_TOO_LARGE, "Файл превышает допустимый размер", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ApiResponse(responseCode = "400", description = "Отсутствует обязательная часть multipart-запроса",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(MissingServletRequestPartException ex,
            HttpServletRequest request) {
        logger.warn("Отсутствует обязательная часть запроса '{}'", ex.getRequestPartName());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST,
                "Отсутствует обязательная часть запроса '" + ex.getRequestPartName() + "'",
                request);
    }

    @ExceptionHandler(MultipartException.class)
    @ApiResponse(responseCode = "400", description = "Некорректный multipart-запрос",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMultipartException(MultipartException ex, HttpServletRequest request) {
        logger.warn("Ошибка разбора multipart-запроса {}: {}", LogSanitizer.uri(request.getRequestURI()),
                ex.getMessage());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректный multipart-запрос", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ApiResponse(responseCode = "400", description = "Нечитаемое тело запроса",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        logger.warn("Нечитаемое тело запроса {}: {}", LogSanitizer.uri(request.getRequestURI()), ex.getMessage());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректное тело запроса", request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    @ApiResponse(responseCode = "400", description = "Недопустимое поле сортировки",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handlePropertyReference(PropertyReferenceException ex,
            HttpServletRequest request) {
        logger.warn("Недопустимое поле сортировки: {}", ex.getPropertyName());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Недопустимое поле сортировки: " + ex.getPropertyName(),
                request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ApiResponse(responseCode = "405", description = "Метод не поддерживается для ресурса",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        logger.warn("Метод {} не поддерживается для {}", ex.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.METHOD_NOT_ALLOWED, "Метод " + ex.getMethod() + " не поддерживается",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ApiResponse(responseCode = "415", description = "Неподдерживаемый тип содержимого",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        logger.warn("Неподдерживаемый тип содержимого: {}", ex.getContentType());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого", request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @ApiResponse(responseCode = "404", description = "Маршрут или ресурс не найден",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleNoHandler(Exception ex, HttpServletRequest request) {
        logger.warn("Маршрут не найден: {} {}", request.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, "Ресурс не найден", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ApiResponse(responseCode = "409", description = "Конфликт версий (@Version) — повторите с актуальной версией",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {
        logger.warn("Конфликт версий при сохранении объекта: {}", ex.getMessage());
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.CONFLICT,
                "Конфликт версий, проверьте актуальность данных и попробуйте снова", request);
    }

    @ExceptionHandler(MailSendException.class)
    @ApiResponse(responseCode = "503", description = "Ошибка отправки email",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMailSendException(MailSendException ex, HttpServletRequest request) {
        Throwable rootCause = ex.getCause();
        logger.error("Ошибка при отправке email: {}", rootCause != null ? rootCause.getMessage() : ex.getMessage(), ex);
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Ошибка отправки email", request);
    }

    @ExceptionHandler(MailException.class)
    @ApiResponse(responseCode = "503", description = "Почтовая система недоступна",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMailException(MailException ex, HttpServletRequest request) {
        logger.error("Почтовый модуль недоступен: {}", ex.getMessage(), ex);
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Почтовая система столкнулась с ошибкой",
                request);
    }

    @ExceptionHandler(MessagingException.class)
    @ApiResponse(responseCode = "503", description = "Ошибка MIME или SMTP при отправке email",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMessagingException(MessagingException ex, HttpServletRequest request) {
        logger.error("MIME или SMTP ошибка: {}", ex.getMessage(), ex);
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Ошибка отправки email", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex,
            HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = ex.getReason() != null ? ex.getReason() : resolved.getReasonPhrase();
        logger.warn("Ошибка ответа {}: {}", resolved.value(), detail);
        ProblemObservation.mark(ex, request);
        return ProblemResponses.entity(resolved, detail, request);
    }
}

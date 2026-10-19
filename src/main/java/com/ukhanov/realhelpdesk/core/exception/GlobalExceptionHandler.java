package com.ukhanov.realhelpdesk.core.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;

import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Глобальный обработчик исключений REST API: все ошибки уходят в единый формат application/problem+json (RFC 7807 + timestamp).
 *
 * <p>
 * Методы размечены {@link ApiResponse} — springdoc тиражирует их в описание каждой операции, поэтому в swagger-ui видны коды 4xx/5xx и
 * структура ответа ошибки (см. OpenApiDocumentationGuardTest).
 * </p>
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    /**
     * Провал капчи: machine-readable {@code code} (missing_token/pow_failed/…) в problem+json — по нему клиент различает «решить PoW ещё
     * раз», «подождать» и «токен чужого действия», а алерты — по тегу reason метрики captcha.verification.
     */
    @ExceptionHandler(CaptchaException.class)
    @ApiResponse(responseCode = "400", description = "Провал капчи (problem+json с машинным полем code)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleCaptchaException(CaptchaException ex, HttpServletRequest request) {
        logger.warn("Капча не пройдена ({}): {}", ex.getReason().code(), ex.getMessage());
        markObserved(ex, request);
        ProblemDetail problem = ProblemResponses.of(ex.getStatus(), ex.getMessage(), request);
        problem.setProperty("code", ex.getReason().code());
        return ResponseEntity.status(ex.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(ApiException.class)
    @ApiResponse(responseCode = "400", description = "Некорректный запрос",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Неаутентифицирован (нет или истёк токен)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Доступ запрещён",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Ресурс не найден",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Конфликт версий или состояния",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "412", description = "Устаревшая версия ресурса (If-Match)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Слишком много запросов (лимит)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Сервис недоступен",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        logger.warn("Ошибка API {}: {}", ex.getStatus().value(), ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(ex.getStatus(), ex.getMessage(), request);
    }

    @ExceptionHandler(ApiRuntimeException.class)
    @ApiResponse(responseCode = "400", description = "Некорректный запрос",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Неаутентифицирован (нет или истёк токен)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Доступ запрещён",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Слишком много запросов (лимит)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Сервис недоступен (хранилище/почта)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleApiRuntimeException(ApiRuntimeException ex, HttpServletRequest request) {
        logger.warn("Ошибка API {}: {}", ex.getStatus().value(), ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(ex.getStatus(), ex.getMessage(), request);
    }

    @ExceptionHandler({UsernameNotFoundException.class, BadCredentialsException.class})
    @ApiResponse(responseCode = "401", description = "Неверный логин или пароль",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure(Exception ex, HttpServletRequest request) {
        // Сообщение UsernameNotFoundException содержит email клиента: в лог адрес маскируется
        logger.warn("Ошибка аутентификации: {}", LogSanitizer.emails(ex.getMessage()));
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    @ApiResponse(responseCode = "403", description = "Доступ запрещён (нет прав или нет CSRF-заголовка)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleAuthorizationDenied(AuthorizationDeniedException ex, HttpServletRequest request) {
        logger.warn("Доступ запрещен: {}", ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.FORBIDDEN, "Доступ запрещен", request);
    }

    /**
     * Ошибка внутри SpEL-выражения {@code @PreAuthorize} (PortalException/TicketException, например «заявка не найдена») оборачивается
     * фреймворком в IllegalArgumentException: без распаковки клиент получал бы 500 вместо 404 — и у сообщений, и у вложений. Если
     * ApiException в причине нет, это обычная ошибка программиста — прежние 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ApiResponse(responseCode = "404", description = "Ресурс не найден (ошибка доступа в SpEL)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ApiException cause = findApiException(ex);
        if (cause != null) {
            logger.warn("Ошибка API {} (из выражения доступа): {}", cause.getStatus().value(), cause.getMessage());
            markObserved(ex, request);
            return ProblemResponses.entity(cause.getStatus(), cause.getMessage(), request);
        }
        logger.error("Перехвачено необработанное исключение: {}", ex.getMessage(), ex);
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.INTERNAL_SERVER_ERROR, "Операция завершилась неудачей", request);
    }

    /** Ищет ApiException в цепочке причин: SpEL оборачивает исключение сервиса минимум дважды. */
    private ApiException findApiException(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ApiException apiException) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
    }

    @ExceptionHandler(JwtException.class)
    @ApiResponse(responseCode = "401", description = "Некорректный или недействительный токен",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleJwtException(JwtException ex, HttpServletRequest request) {
        logger.warn("JWT ошибка: {}", ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.UNAUTHORIZED, "Некорректный или недействительный токен", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ApiResponse(responseCode = "400", description = "Ошибка валидации тела запроса (поля в errors)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                errors.put(fieldError.getField(), fieldError.getDefaultMessage());
            } else {
                errors.put(error.getObjectName(), error.getDefaultMessage());
            }
        });
        // Имена полей — в WARN, чтобы видно, какое поле отклонено (@NoHtml, @Size и т.п.);
        // значения не логируем: отклонённая разметка как раз и есть содержимое поля
        logger.warn("Ошибка валидации тела запроса {}: поля {}", LogSanitizer.uri(request.getRequestURI()), errors.keySet());

        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректный запрос", request, errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ApiResponse(responseCode = "400", description = "Некорректное значение query-параметра",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        logger.warn("Некорректное значение параметра '{}': {}", ex.getName(), ex.getValue());

        String expected = ex.getRequiredType() != null ? "Ожидаемый тип: " + ex.getRequiredType().getSimpleName() + ". " : "";
        Map<String, String> errors = Map.of(ex.getName(), expected + "Получено: " + ex.getValue());

        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректный параметр запроса", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ApiResponse(responseCode = "400", description = "Нарушение ограничений запроса",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        logger.warn("Нарушение ограничений запроса: {}", ex.getConstraintViolations().size());

        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректный запрос", request, errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ApiResponse(responseCode = "400", description = "Отсутствует обязательный query-параметр",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        logger.warn("Отсутствует обязательный параметр '{}'", ex.getParameterName());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Отсутствует обязательный параметр '" + ex.getParameterName() + "'",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ApiResponse(responseCode = "413", description = "Файл превышает допустимый размер",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        logger.warn("Загружаемый файл превышает лимит: {}", ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.PAYLOAD_TOO_LARGE, "Файл превышает допустимый размер", request);
    }

    /** Отсутствующая часть multipart (например, file у загрузки вложения) — 400, а не 500 из общего обработчика. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    @ApiResponse(responseCode = "400", description = "Отсутствует обязательная часть multipart-запроса",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        logger.warn("Отсутствует обязательная часть запроса '{}'", ex.getRequestPartName());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Отсутствует обязательная часть запроса '" + ex.getRequestPartName() + "'",
                request);
    }

    @ExceptionHandler(MultipartException.class)
    @ApiResponse(responseCode = "400", description = "Некорректный multipart-запрос",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMultipartException(MultipartException ex, HttpServletRequest request) {
        logger.warn("Ошибка разбора multipart-запроса {}: {}", LogSanitizer.uri(request.getRequestURI()), ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректный multipart-запрос", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ApiResponse(responseCode = "400", description = "Нечитаемое тело запроса",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logger.warn("Нечитаемое тело запроса {}: {}", LogSanitizer.uri(request.getRequestURI()), ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Некорректное тело запроса", request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    @ApiResponse(responseCode = "400", description = "Недопустимое поле сортировки",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handlePropertyReference(PropertyReferenceException ex, HttpServletRequest request) {
        logger.warn("Недопустимое поле сортировки: {}", ex.getPropertyName());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "Недопустимое поле сортировки: " + ex.getPropertyName(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ApiResponse(responseCode = "405", description = "Метод не поддерживается для ресурса",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        logger.warn("Метод {} не поддерживается для {}", ex.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.METHOD_NOT_ALLOWED, "Метод " + ex.getMethod() + " не поддерживается", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ApiResponse(responseCode = "415", description = "Неподдерживаемый тип содержимого",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        logger.warn("Неподдерживаемый тип содержимого: {}", ex.getContentType());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого", request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @ApiResponse(responseCode = "404", description = "Маршрут или ресурс не найден",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleNoHandler(Exception ex, HttpServletRequest request) {
        logger.warn("Маршрут не найден: {} {}", request.getMethod(), LogSanitizer.uri(request.getRequestURI()));
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.NOT_FOUND, "Ресурс не найден", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ApiResponse(responseCode = "409", description = "Конфликт версий (@Version) — повторите с актуальной версией",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        logger.warn("Конфликт версий при сохранении объекта: {}", ex.getMessage());
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.CONFLICT, "Конфликт версий, проверьте актуальность данных и попробуйте снова", request);
    }

    @ExceptionHandler(MailSendException.class)
    @ApiResponse(responseCode = "503", description = "Ошибка отправки email",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMailSendException(MailSendException ex, HttpServletRequest request) {
        Throwable rootCause = ex.getCause();
        logger.error("Ошибка при отправке email: {}", rootCause != null ? rootCause.getMessage() : ex.getMessage(), ex);
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Ошибка отправки email", request);
    }

    @ExceptionHandler(MailException.class)
    @ApiResponse(responseCode = "503", description = "Почтовая система недоступна",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMailException(MailException ex, HttpServletRequest request) {
        logger.error("Почтовый модуль недоступен: {}", ex.getMessage(), ex);
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Почтовая система столкнулась с ошибкой", request);
    }

    @ExceptionHandler(MessagingException.class)
    @ApiResponse(responseCode = "503", description = "Ошибка MIME или SMTP при отправке email",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleMessagingException(MessagingException ex, HttpServletRequest request) {
        logger.error("MIME или SMTP ошибка: {}", ex.getMessage(), ex);
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.SERVICE_UNAVAILABLE, "Ошибка отправки email", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = ex.getReason() != null ? ex.getReason() : resolved.getReasonPhrase();
        logger.warn("Ошибка ответа {}: {}", resolved.value(), detail);
        markObserved(ex, request);
        return ProblemResponses.entity(resolved, detail, request);
    }

    @ExceptionHandler(Exception.class)
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex, HttpServletRequest request) {
        logger.error("Перехвачено необработанное исключение: {}", ex.getMessage(), ex);
        markObserved(ex, request);
        return ProblemResponses.entity(HttpStatus.INTERNAL_SERVER_ERROR, "Операция завершилась неудачей", request);
    }

    /**
     * Отмечает исключение в контексте наблюдения запроса. Без этого ServerHttpObservationFilter ничего не знает о
     * перехваченном @ControllerAdvice исключении (DispatcherServlet вернул ответ штатно), и в теге exception метрики http.server.requests
     * стоял бы none.
     */
    private void markObserved(Throwable ex, HttpServletRequest request) {
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> context.setError(ex));
    }
}

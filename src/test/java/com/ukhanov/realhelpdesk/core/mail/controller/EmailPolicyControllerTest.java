package com.ukhanov.realhelpdesk.core.mail.controller;

import java.util.List;

import jakarta.mail.MessagingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.ukhanov.realhelpdesk.core.config.ClientIpProperties;
import com.ukhanov.realhelpdesk.core.exception.GlobalExceptionHandler;
import com.ukhanov.realhelpdesk.core.http.ClientIpResolver;
import com.ukhanov.realhelpdesk.core.mail.dto.EmailInfoResponse;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.mail.service.EmailPolicyService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaReason;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaTokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("REST-контракт почтовой политики (EmailPolicyController)")
class EmailPolicyControllerTest {

    @Mock
    private EmailDeliveryService emailDeliveryService;

    @Mock
    private EmailPolicyService emailPolicyService;

    @Mock
    private CaptchaService captchaService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcTester.of(
                List.of(new EmailPolicyController(emailDeliveryService, emailPolicyService, captchaService,
                        new ClientIpResolver(new ClientIpProperties()))),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    // ────────────────────────────────────────────────
    // Подтверждение адреса
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /email/confirmations/{token} → 204")
    void confirmEmail_validToken_returnsNoContent() throws Exception {
        // given / when / then
        assertThat(mockMvc.post().uri("/api/v1/email/confirmations/123456")).hasStatus(HttpStatus.NO_CONTENT);

        verify(emailDeliveryService).confirmEmail(123456L);
    }

    @Test
    @DisplayName("Неверный код подтверждения → 403 (problem+json)")
    void confirmEmail_wrongToken_returnsForbidden() throws Exception {
        // given
        doThrow(new EmailAccessDeniedException("Код подтверждения права владения почтой не верный")).when(emailDeliveryService)
                .confirmEmail(123456L);

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/confirmations/123456").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(403);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Код подтверждения права владения почтой не верный");
    }

    @Test
    @DisplayName("Код подтверждения не число → 400, сервис не вызывается")
    void confirmEmail_nonNumericToken_returnsBadRequest() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/confirmations/abc").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);

        verify(emailDeliveryService, never()).confirmEmail(any());
    }

    // ────────────────────────────────────────────────
    // Информация об отписках
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /email/info → 204? нет, 200 с текущим уровнем глушения")
    void getInfo_eventMuted_returnsMuteLevel() throws Exception {
        // given
        EmailInfoResponse response = new EmailInfoResponse(NotificationEvent.NEW_TICKET);
        when(emailPolicyService.getEmailInfo()).thenReturn(response);

        // when
        MvcTestResult result = mockMvc.get().uri("/api/v1/email/info").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.muteLevel").asString().isEqualTo("NEW_TICKET");
    }

    // ────────────────────────────────────────────────
    // Отписка от уведомлений
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /email/notifications/{event} → 204 и событие сохраняется")
    void stopNotifications_knownEvent_returnsNoContent() throws Exception {
        // given / when / then
        assertThat(mockMvc.delete().uri("/api/v1/email/notifications/CHANGE_TICKET")).hasStatus(HttpStatus.NO_CONTENT);

        verify(emailPolicyService).addToStopList(NotificationEvent.CHANGE_TICKET);
    }

    @Test
    @DisplayName("Неизвестное событие → 400, стоп-лист не меняется")
    void stopNotifications_unknownEvent_returnsBadRequest() throws Exception {
        // given / when
        MvcTestResult result = mockMvc.delete().uri("/api/v1/email/notifications/NOT_AN_EVENT").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(400);

        verify(emailPolicyService, never()).addToStopList(any());
    }

    // ────────────────────────────────────────────────
    // Код подтверждения
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /email/codes c X-Captcha-Token → 202, токен проверен, письмо отправлено")
    void sendConfirmCode_validCaptcha_returnsAccepted() throws Exception {
        // given / when / then
        assertThat(mockMvc.post().uri("/api/v1/email/codes").header("X-Captcha-Token", "jwt-token")).hasStatus(HttpStatus.ACCEPTED);

        verify(captchaService).verify("jwt-token", CaptchaTokenService.ACTION_EMAIL_CODE, "127.0.0.1");
        verify(emailDeliveryService).sendConfirmCode();
    }

    @Test
    @DisplayName("Капча не пройдена → 400 с code, письмо не отправляется")
    void sendConfirmCode_captchaFailed_returnsBadRequest() throws Exception {
        // given
        doThrow(new CaptchaException("Провал прохождения капчи", CaptchaReason.POW_FAILED)).when(captchaService).verify(any(),
                eq(CaptchaTokenService.ACTION_EMAIL_CODE), any());

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/codes").header("X-Captcha-Token", "bad").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Провал прохождения капчи");
        assertThat(result).bodyJson().extractingPath("$.code").asString().isEqualTo("pow_failed");

        verify(emailDeliveryService, never()).sendConfirmCode();
    }

    @Test
    @DisplayName("Почтовый сервер недоступен → 503 (MailSendException)")
    void sendConfirmCode_mailServerDown_returnsServiceUnavailable() throws Exception {
        // given
        doThrow(new MailSendException("Connection refused")).when(emailDeliveryService).sendConfirmCode();

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/codes").header("X-Captcha-Token", "jwt-token").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(503);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Ошибка отправки email");
    }

    @Test
    @DisplayName("Ошибка MIME/SMTP → 503 (MessagingException)")
    void sendConfirmCode_messagingFailure_returnsServiceUnavailable() throws Exception {
        // given
        doThrow(new MessagingException("SMTP banner mismatch")).when(emailDeliveryService).sendConfirmCode();

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/codes").header("X-Captcha-Token", "jwt-token").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Ошибка отправки email");
    }

    @Test
    @DisplayName("Исчерпан лимит писем → 429 (EmailAccessDeniedException c TOO_MANY_REQUESTS)")
    void sendConfirmCode_rateLimited_returnsTooManyRequests() throws Exception {
        // given
        doThrow(new EmailAccessDeniedException("Исчерпан лимит", HttpStatus.TOO_MANY_REQUESTS)).when(emailDeliveryService)
                .sendConfirmCode();

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/email/codes").header("X-Captcha-Token", "jwt-token").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result).bodyJson().extractingPath("$.status").asNumber().isEqualTo(429);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isEqualTo("Исчерпан лимит");
    }

    @Test
    @DisplayName("Без заголовка капчи сервис кода вызывается, проверку выполняет капча")
    void sendConfirmCode_withoutCaptchaHeader_stillCallsService() throws Exception {
        // given / when / then
        assertThat(mockMvc.post().uri("/api/v1/email/codes")).hasStatus(HttpStatus.ACCEPTED);

        verify(captchaService).verify(null, CaptchaTokenService.ACTION_EMAIL_CODE, "127.0.0.1");
        verify(emailDeliveryService).sendConfirmCode();
    }

    @Test
    @DisplayName("Неизвестный маршрут почтовой политики → 404")
    void routing_unknownRoute_returnsNotFound() throws Exception {
        // given / when / then
        assertThat(mockMvc.get().uri("/api/v1/email/unknown")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("eq-проверка: событие передаётся в сервис без изменений")
    void stopNotifications_exactEvent_passesUnchanged() { // given / when / then
        assertThat(mockMvc.delete().uri("/api/v1/email/notifications/{event}", NotificationEvent.NEW_TICKET_OR_MESSAGE))
                .hasStatus(HttpStatus.NO_CONTENT);

        verify(emailPolicyService).addToStopList(eq(NotificationEvent.NEW_TICKET_OR_MESSAGE));
    }
}

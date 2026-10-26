package com.ukhanov.realhelpdesk.core.mail.service;

import java.time.Year;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ukhanov.realhelpdesk.core.mail.exception.EmailTemplateRenderException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.service.EmailTemplateRenderService.EmailContent;
import com.ukhanov.realhelpdesk.core.mail.support.EmailContentFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Рендер писем: EmailTemplateRenderService")
class EmailTemplateRenderServiceTest {

    private final EmailTemplateRenderService renderer = EmailContentFixture.renderer();

    @Test
    @DisplayName("Тема собирается из шаблона enum, тело — из HTML-шаблона с параметрами")
    void render_ticketStatus_resolvesSubjectAndBody() {

        // given
        Map<String, Object> model = Map.of("ticketId", 77L, "portalId", 5L, "status", "CLOSED");

        // when
        EmailContent content = renderer.render(EmailTemplateId.TICKET_STATUS, model);

        // then
        assertThat(content.subject()).isEqualTo("Задан статус:CLOSED для заявки#77");
        assertThat(content.html())
                .contains("<!DOCTYPE html>")
                .contains("/ticket-show/portal/5/ticket/77");
    }

    @Test
    @DisplayName("Длинная тема обрезается с многоточием — заголовок письма не ломается")
    void render_overlongSubject_truncatedWithEllipsis() {

        // given
        Map<String, Object> model = EmailContentFixture.fullModel();
        model.put("title", "Очень длинный заголовок уведомления ".repeat(10));

        // when
        EmailContent content = renderer.render(EmailTemplateId.NOTIFICATION_REPEAT, model);

        // then
        assertThat(content.subject()).endsWith("\u2026").hasSizeLessThanOrEqualTo(78);
    }

    @ParameterizedTest
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Общие значения (проект, домен, год) подставляются без передачи их вызывающим")
    void render_anyTemplate_addsCommonValues(EmailTemplateId template) {

        // given
        // when
        EmailContent content = renderer.render(template, EmailContentFixture.fullModel());

        // then
        assertThat(content.html())
                .contains(EmailContentFixture.PROJECT_NAME)
                .contains(String.valueOf(Year.now().getValue()));
    }

    @Test
    @DisplayName("Отсутствующий обязательный ключ модели — предсказуемая ошибка, а не письмо с пустым местом")
    void render_missingRequiredKey_throwsRenderException() {

        // given — в модели только ticketId, без portalId
        Map<String, Object> incomplete = Map.of("ticketId", 77L);

        // when / then
        assertThatThrownBy(() -> renderer.render(EmailTemplateId.TICKET_CREATED, incomplete))
                .isInstanceOf(EmailTemplateRenderException.class)
                .hasMessage("Не удалось собрать письмо");
    }

    @Test
    @DisplayName("null-модель не падает: обязательные ключи всё равно требуются, а общие значения считаются")
    void render_nullModel_throwsForRequiredKeys() {

        // given / when / then
        assertThatThrownBy(() -> renderer.render(EmailTemplateId.PORTAL_CREATED, null))
                .isInstanceOf(EmailTemplateRenderException.class);
    }

    @Test
    @DisplayName("Токен из модели попадает в тело письма сброса под ключом code")
    void render_tokenNormalizedToCode_linkContainsToken() {

        // given
        Map<String, Object> model = new HashMap<>();
        model.put("token", "999888777666555444");

        // when
        EmailContent content = renderer.render(EmailTemplateId.PASSWORD_RESET, model);

        // then
        assertThat(content.subject()).isEqualTo("Сброс пароля");
        assertThat(content.html()).contains("999888777666555444");
    }

    @Test
    @DisplayName("Ошибка рендера не тащит содержимое модели в сообщение исключения")
    void render_missingKey_messageHasNoModelValues() {

        // given
        Map<String, Object> secretModel = new HashMap<>();
        secretModel.put("ticketId", 77L);
        secretModel.put("code", "1234567890123456");

        // when / then
        assertThatThrownBy(() -> renderer.render(EmailTemplateId.TICKET_CREATED, secretModel))
                .isInstanceOf(EmailTemplateRenderException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("1234567890123456")
                        .doesNotContain("77"));
    }
}

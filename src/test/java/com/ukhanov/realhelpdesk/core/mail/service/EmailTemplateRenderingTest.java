package com.ukhanov.realhelpdesk.core.mail.service;

import java.time.Year;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.TemplateEngine;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.support.EmailContentFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный рендер всех шаблонов писем из реального classpath (`target/classes/templates/mail`): ловит битую вёрстку и
 * опечатки в путях/ключах модели до первой отправки.
 *
 * <p>
 * Статическая согласованность enum'а и файлов проверяет {@code EmailTemplateGuardTest}; здесь — именно исполнение
 * Thymeleaf: фрагменты, выражения, обязательные ключи модели.
 * </p>
 */
@DisplayName("Рендер всех шаблонов писем из classpath")
class EmailTemplateRenderingTest {

    private final TemplateEngine templateEngine = EmailContentFixture.templateEngine();
    private final EmailProperties emailProperties = EmailContentFixture.emailProperties();

    private EmailTemplateRenderService renderer() {
        return new EmailTemplateRenderService(templateEngine, emailProperties);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Шаблон с полной моделью рендерится в готовое письмо")
    void render_fullModel_producesSubjectAndHtml(EmailTemplateId template) {

        // given
        // when
        EmailTemplateRenderService.EmailContent content = renderer().render(template,
                EmailContentFixture.fullModel());

        // then
        assertThat(content.subject()).isNotBlank();
        assertThat(content.html()).contains("<!DOCTYPE html>");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Рендер содержит содержимое шапки и футера")
    void render_fullModel_rendersHeaderAndFooter(EmailTemplateId template) {

        // given
        // when
        EmailTemplateRenderService.EmailContent content = renderer().render(template,
                EmailContentFixture.fullModel());

        // then
        // th:replace вырезает хост-тег вместе с атрибутом, поэтому проверяется результат подстановки фрагментов,
        // а не маркер фрагмента: шапка — заголовок с именем проекта, футер — копирайт с текущим годом.
        assertThat(content.html())
                .as("Шаблон %s должен содержать шапку с именем проекта", template.name())
                .contains("<h1 style=\"margin: 0; font-size: 20px; font-weight: 600; color: #ffffff;\">"
                        + EmailContentFixture.PROJECT_NAME + "</h1>");
        assertThat(content.html())
                .as("Шаблон %s должен содержать футер с текущим годом", template.name())
                .contains("© <span>" + Year.now().getValue() + "</span> <span>" + EmailContentFixture.PROJECT_NAME
                        + "</span>");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Вёрстка письма: lang, charset, табочная сетка 600px и инлайн-стили")
    void render_fullModel_keepsMailMarkupContract(EmailTemplateId template) {

        // given
        // when
        EmailTemplateRenderService.EmailContent content = renderer().render(template,
                EmailContentFixture.fullModel());

        // then
        assertThat(content.html()).contains("lang=\"ru\"").contains("<meta charset=\"UTF-8\"/>");
        assertThat(content.html()).contains("<meta name=\"viewport\"");
        // Почтовые клиенты вырезают <style> и внешние CSS: layout — таблица 600px, стили только инлайновые.
        assertThat(content.html()).contains("role=\"presentation\"").contains("width=\"600\"");
        assertThat(content.html()).doesNotContain("<style").doesNotContain("<link");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Шаблон рендерится ровно той моделью, которую шлёт вызывающий сервис")
    void render_requiredKeysOnly_producesHtml(EmailTemplateId template) {

        // given
        // Модель — только обязательные ключи темы плюс общие значения рендерера: если шаблон обращается к ключу,
        // которого контракт не обещает, рендер провалится здесь, а не в проде.
        Map<String, Object> minimalModel = minimalModelOf(template);

        // when
        EmailTemplateRenderService.EmailContent content = renderer().render(template, minimalModel);

        // then
        assertThat(content.html()).doesNotContain("th:text");
    }

    @Test
    @DisplayName("Письма с кодом имеют plain-часть, остальные — только HTML")
    void render_plainRequiredTemplates_havePlainPartWithCode() {

        // given
        // when / then
        for (EmailTemplateId template : EmailTemplateId.values()) {
            EmailTemplateRenderService.EmailContent content = renderer().render(template,
                    EmailContentFixture.fullModel());
            if (template.needsPlainPart()) {
                assertThat(content.hasPlainPart())
                        .as("Шаблон %s обязан отдавать код в plain-части для e2e-скриптов", template.name())
                        .isTrue();
                assertThat(content.plain()).contains(EmailContentFixture.CODE).doesNotContain("<");
            } else {
                assertThat(content.hasPlainPart())
                        .as("Шаблон %s не должен иметь plain-части", template.name())
                        .isFalse();
            }
        }
    }

    /** Минимальная модель: обязательные ключи темы; REGISTRATION_LINK передаёт ссылку через token, как сервис. */
    private Map<String, Object> minimalModelOf(EmailTemplateId template) {
        Map<String, Object> full = EmailContentFixture.fullModel();
        Map<String, Object> minimal = new java.util.LinkedHashMap<>();
        for (String key : template.requiredModelKeys()) {
            minimal.put(key, full.get(key));
        }
        if (template == EmailTemplateId.REGISTRATION_LINK) {
            minimal.remove("token");
            minimal.put("token", EmailContentFixture.CODE);
        }
        return minimal;
    }
}

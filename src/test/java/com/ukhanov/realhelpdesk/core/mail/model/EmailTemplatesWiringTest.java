package com.ukhanov.realhelpdesk.core.mail.model;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;

import com.ukhanov.realhelpdesk.core.mail.support.EmailTemplatesFixture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Шаблоны писем: подключение MessageSource в контексте Spring")
class EmailTemplatesWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class));

    @Test
    @DisplayName("MessageSource автоматически подключается и читает сообщения из properties")
    void messageSource_autoConfigured_readsMessagesProperties() {
        // given / when / then
        runner.run(context -> {
            assertThat(context).hasSingleBean(MessageSource.class);
            MessageSource messageSource = context.getBean(MessageSource.class);

            assertThat(messageSource.getMessage("email.ticket.reply.subject", new Object[]{"77"}, Locale.ENGLISH))
                    .isEqualTo("[Заявка#77] Новое сообщение");
            assertThat(messageSource.getMessage("email.registration.link.subject", null, Locale.GERMAN))
                    .isEqualTo("Подтверждение регистрации");
        });
    }

    @Test
    @DisplayName("EmailTemplates рендерит письма через автоконфигурированный MessageSource")
    void emailTemplates_messageSourceFromContext_rendersLocalizedText() {
        // given / when / then
        runner.run(context -> {
            EmailTemplates emailTemplates = new EmailTemplates(context.getBean(MessageSource.class),
                    EmailTemplatesFixture.emailProperties());

            assertThat(emailTemplates.ticketReplyBody(77L, 5L)).contains("https://front.example.ru/ticket-show/portal/5/ticket/77");
            assertThat(emailTemplates.passwordResetSubject()).isEqualTo("Сброс пароля");
        });
    }
}

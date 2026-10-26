package com.ukhanov.realhelpdesk.core.mail.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ukhanov.realhelpdesk.core.mail.support.EmailContentFixture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Типы писем: EmailTemplateId")
class EmailTemplateIdTest {

    private static final Pattern SUBJECT_PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    @Test
    @DisplayName("Пути к шаблонам уникальны — два типа писем не ссылаются на один HTML")
    void values_distinctPaths_noCollisions() {

        // given
        // when
        Set<String> paths = Arrays.stream(EmailTemplateId.values())
                .map(EmailTemplateId::templatePath)
                .collect(Collectors.toSet());

        // then
        assertThat(paths).hasSize(EmailTemplateId.values().length);
    }

    @ParameterizedTest
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Путь без расширения и с префиксом mail/, тема не пустая")
    void value_contract_isNonEmpty(EmailTemplateId template) {

        // given / when / then
        assertThat(template.templatePath())
                .startsWith("mail/")
                .doesNotContain(".html");
        assertThat(template.subjectTemplate()).isNotBlank();
        assertThat(template.requiredModelKeys()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Каждый плейсхолдер темы объявлен обязательным ключом модели — тема соберётся у вызывающего")
    void value_subjectPlaceholders_areDeclaredKeys(EmailTemplateId template) {

        // given
        Matcher matcher = SUBJECT_PLACEHOLDER.matcher(template.subjectTemplate());

        // when
        Set<String> placeholders = new HashSet<>();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }

        // then
        assertThat(placeholders)
                .as("плейсхолдеры темы %s", template.name())
                .isSubsetOf(template.requiredModelKeys());
    }

    @Test
    @DisplayName("Тема подставляет значения модели и обрезается по пределу длины с многоточием")
    void resolveSubject_longValues_truncatesWithEllipsis() {

        // given
        EmailTemplateId template = EmailTemplateId.NOTIFICATION_REPEAT;
        String longTitle = "Очень длинный заголовок уведомления ".repeat(10);

        // when
        String subject = template.resolveSubject(Map.of("title", longTitle));

        // then
        assertThat(subject)
                .endsWith("\u2026")
                .hasSizeLessThanOrEqualTo(78)
                .startsWith("[Напоминание] ");
    }

    @Test
    @DisplayName("Тема печатает enum-значения статуса и приоритета как есть")
    void resolveSubject_statusAndPriority_printsEnumValue() {

        // given / when
        String status = EmailTemplateId.TICKET_STATUS
                .resolveSubject(Map.of("ticketId", 77L, "portalId", 5L, "status", "OPEN"));
        String priority = EmailTemplateId.TICKET_PRIORITY
                .resolveSubject(Map.of("ticketId", 77L, "portalId", 5L, "priority", "HIGH"));

        // then
        assertThat(status).isEqualTo("Задан статус:OPEN для заявки#77");
        assertThat(priority).isEqualTo("Задан приоритет:HIGH для заявки#77");
    }

    @Test
    @DisplayName("Тема не растёт из примеров фикстуры длиннее предела")
    void resolveSubject_fixtureModel_staysWithinLimit() {

        // given / when
        String subject = EmailTemplateId.NOTIFICATION_REPEAT
                .resolveSubject(EmailContentFixture.fullModel());

        // then
        assertThat(subject).isEqualTo("[Напоминание] Заголовок уведомления");
    }
}

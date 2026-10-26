package com.ukhanov.realhelpdesk.core.mail.service;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateProcessingException;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailTemplateRenderException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Рендер писем: тема из шаблона {@link EmailTemplateId}, тело — из Thymeleaf-шаблона.
 *
 * <p>
 * Модель дополняется общими значениями ({@code projectName}, {@code baseUrl}, {@code currentYear}) — вызывающий их не
 * передаёт. Ссылка письма с кодом/токеном всегда в {@code code}: {@code REGISTRATION_LINK} хранит её в {@code token}, и
 * без нормализации шаблоны требовали бы разных ключей модели.
 * </p>
 *
 * <p>
 * Обязательные ключи модели ({@link EmailTemplateId#requiredModelKeys()}) проверяются до рендера: отсутствующий
 * параметр — предсказуемая {@link EmailTemplateRenderException}, а не письмо с пустым местом.
 * </p>
 *
 * <p>
 * Ошибка рендера подменяется {@link EmailTemplateRenderException}: сообщение Thymeleaf содержит имя шаблона и фрагменты
 * контекста, в лог и в ответ клиента оно уходить не должно.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class EmailTemplateRenderService {

    private final TemplateEngine templateEngine;
    private final EmailProperties emailProperties;

    public EmailContent render(EmailTemplateId template, Map<String, Object> model) {
        Map<String, Object> values = withCommonValues(model);
        validateRequiredKeys(template, values);
        String subject = template.resolveSubject(values);

        Context context = new Context();
        values.forEach(context::setVariable);
        String html;
        try {
            // Расширение добавляет SpringResourceTemplateResolver (spring.thymeleaf.suffix) — путь из enum без .html
            html = templateEngine.process(template.templatePath(), context);
        } catch (TemplateProcessingException e) {
            // templatePath — путь из конечного enum, он безопасен; сообщение Thymeleaf — нет
            logger.error("Не удалось отрендерить шаблон письма {}", template.name());
            throw new EmailTemplateRenderException("Не удалось собрать письмо", e);
        }
        return new EmailContent(subject, html);
    }

    /** Общие значения поверх переданной модели: вызывающий передаёт только содержательные параметры. */
    private Map<String, Object> withCommonValues(Map<String, Object> model) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (model != null) {
            model.forEach((key, value) -> values.put(key, value != null ? value : ""));
        }
        String domain = emailProperties.getDomain() != null ? emailProperties.getDomain() : "";
        values.put("projectName", emailProperties.getProjectName() != null ? emailProperties.getProjectName() : "");
        values.put("baseUrl", "http://" + domain);
        values.put("currentYear", Year.now().getValue());
        if (values.containsKey("token")) {
            values.put("code", values.get("token"));
        }
        return values;
    }

    /**
     * Обязательные ключи модели: без них тема и тело собрались бы с пустыми местами (код подтверждения исчез из письма,
     * ссылка — тоже). В лог попадают только имена ключей и шаблона — конечные enum-значения, значения модели не
     * тащатся.
     */
    private void validateRequiredKeys(EmailTemplateId template, Map<String, Object> values) {
        List<String> missing = template.requiredModelKeys()
                .stream()
                .filter(key -> !values.containsKey(key))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            logger.error("В модели шаблона {} нет обязательных ключей: {}", template.name(), missing);
            throw new EmailTemplateRenderException("Не удалось собрать письмо", null);
        }
    }

    /** Тема и HTML-тело готового письма. */
    public record EmailContent(String subject, String html) {
    }
}

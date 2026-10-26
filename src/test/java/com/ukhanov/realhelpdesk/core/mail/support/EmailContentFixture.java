package com.ukhanov.realhelpdesk.core.mail.support;

import java.util.HashMap;
import java.util.Map;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.ukhanov.realhelpdesk.core.mail.config.EmailProperties;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.service.EmailTemplateRenderService;

/**
 * Фикстуры рендера писем: EmailProperties, TemplateEngine поверх classpath и полная модель параметров для каждого
 * {@link EmailTemplateId}. Модель здесь — супернабор ключей всех шаблонов: сервисные тесты передают только то, что шлёт
 * конкретный сервис, а рендер-тесты проверяют вёрстку целиком.
 */
public final class EmailContentFixture {

    public static final String DOMAIN = "front.example.ru";
    public static final String PROJECT_NAME = "real help desk";

    public static final Long TICKET_ID = 77L;
    public static final Long PORTAL_ID = 5L;

    /** Код из 16 цифр — той же длины, что ищут регулярки e2e-скриптов. */
    public static final String CODE = "1234567890123456";

    private EmailContentFixture() {
    }

    public static EmailProperties emailProperties() {
        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setDomain(DOMAIN);
        emailProperties.setProjectName(PROJECT_NAME);
        return emailProperties;
    }

    /**
     * SpringTemplateEngine без кэша и без Spring-контекста: шаблоны читаются с того же classpath, что и в приложении.
     * Обычный TemplateEngine не берём — thymeleaf-spring6 исключает OGNL, вычисления идут на SpringEL.
     */
    public static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        return templateEngine;
    }

    public static EmailTemplateRenderService renderer() {
        return new EmailTemplateRenderService(templateEngine(), emailProperties());
    }

    /** Полная модель шаблона: все ключи, которые могут встретиться в письмах, с типовыми значениями. */
    public static Map<String, Object> fullModel() {
        Map<String, Object> values = new HashMap<>();
        values.put("token", CODE);
        values.put("code", CODE);
        values.put("ticketId", TICKET_ID);
        values.put("portalId", PORTAL_ID);
        values.put("status", "CLOSED");
        values.put("priority", "HIGH");
        values.put("deletedByEmail", "user@example.com");
        values.put("initiatorName", "Иванов Иван");
        values.put("newOwnerName", "Петров Пётр");
        values.put("proposedName", "Сидоров Сидор");
        values.put("reason", "Причина передачи");
        values.put("title", "Заголовок уведомления");
        values.put("email", "newuser@example.com");
        values.put("userId", 42L);
        return values;
    }
}

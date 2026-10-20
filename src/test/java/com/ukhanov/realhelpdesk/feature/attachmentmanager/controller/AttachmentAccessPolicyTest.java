package com.ukhanov.realhelpdesk.feature.attachmentmanager.controller;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import com.ukhanov.realhelpdesk.feature.messagemanager.controller.MessageController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Файл в ленте заявки — это сообщение, поэтому политика доступа к вложениям должна совпадать с политикой сообщений заявки: одно и то же
 * выражение {@code hasTicketAccess} на загрузке, в списке и в скачивании.
 */
@DisplayName("Тесты паритета политики доступа: вложения = сообщения заявки")
class AttachmentAccessPolicyTest {

    private static final String TICKET_ACCESS = "@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)";

    @Test
    @DisplayName("Сообщения и вложения используют одно и то же выражение @PreAuthorize")
    void preAuthorize_ticketAccessMethods_returnsSameExpression() {
        // given / when / then
        assertThat(preAuthorize(MessageController.class, "createMessage")).isEqualTo(TICKET_ACCESS);
        assertThat(preAuthorize(MessageController.class, "getAllMessages")).isEqualTo(TICKET_ACCESS);
        assertThat(preAuthorize(AttachmentController.class, "uploadAttachment")).isEqualTo(TICKET_ACCESS);
        assertThat(preAuthorize(AttachmentController.class, "getAttachments")).isEqualTo(TICKET_ACCESS);
        assertThat(preAuthorize(AttachmentController.class, "downloadAttachment")).isEqualTo(TICKET_ACCESS);
    }

    private String preAuthorize(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.getName().equals(methodName)).findFirst()
                .orElseThrow(() -> new AssertionError("Метод " + methodName + " не найден в " + controller.getSimpleName()));
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s.%s без @PreAuthorize", controller.getSimpleName(), methodName).isNotNull();
        return annotation.value();
    }
}

package com.ukhanov.realhelpdesk.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ukhanov.realhelpdesk.feature.ticketmanager.exception.TicketException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ошибки внутри SpEL-выражения {@code @PreAuthorize} фреймворк оборачивает в IllegalArgumentException — без распаковки
 * клиент получал бы 500 вместо 404 (и у сообщений, и у вложений).
 */
@DisplayName("Тесты GlobalExceptionHandler: распаковка ошибки из выражения @PreAuthorize")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET",
            "/api/v1/portals/1/tickets/2/attachments");

    @Test
    @DisplayName("IllegalArgumentException с причиной ApiException → статус и текст ApiException (404), а не 500")
    void illegalArgument_withApiCause_returnsApiStatusAndMessage() {

        // given
        // when
        IllegalArgumentException wrapped = new IllegalArgumentException(
                "Failed to evaluate expression '@ticketAccessValidationService.hasTicketAccess(#portalId, #ticketId)'",
                new RuntimeException(TicketException.notFound("Заявка не найдена!")));

        ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(wrapped, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Заявка не найдена!");
    }

    @Test
    @DisplayName("IllegalArgumentException без ApiException в причине → прежние 500")
    void illegalArgument_withoutApiCause_returnsInternalServerError() {

        // given
        // when
        ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(new IllegalArgumentException("boom"),
                request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Операция завершилась неудачей");
    }

    @Test
    @DisplayName("Причина любой глубины находит ApiException (SpEL оборачивает исключение дважды)")
    void illegalArgument_nestedApiCause_isFound() {

        // given
        // when
        IllegalArgumentException wrapped = new IllegalArgumentException("outer",
                new IllegalStateException(TicketException.notFound("Заявка не найдена!")));

        ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(wrapped, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}

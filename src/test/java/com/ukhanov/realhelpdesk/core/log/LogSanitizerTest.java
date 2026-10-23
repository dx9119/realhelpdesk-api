package com.ukhanov.realhelpdesk.core.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Маскировка секретных сегментов пути: LogSanitizer")
class LogSanitizerTest {

    @Test
    @DisplayName("Код подтверждения email в пути маскируется")
    void uri_emailConfirmationToken_isMasked() {

        // given / when / then
        assertThat(LogSanitizer.uri("/api/v1/email/confirmations/1234567890"))
                .isEqualTo("/api/v1/email/confirmations/***");
    }

    @Test
    @DisplayName("Код восстановления пароля в пути маскируется")
    void uri_passwordResetCode_isMasked() {

        // given / when / then
        assertThat(LogSanitizer.uri("/api/v1/users/password-resets/-987654321012345"))
                .isEqualTo("/api/v1/users/password-resets/***");
    }

    @Test
    @DisplayName("Обычные маршруты не изменяются")
    void uri_regularPaths_areUnchanged() {

        // given / when / then
        assertThat(LogSanitizer.uri("/api/v1/email/info")).isEqualTo("/api/v1/email/info");
        assertThat(LogSanitizer.uri("/api/v1/users")).isEqualTo("/api/v1/users");
    }

    @Test
    @DisplayName("null вместо пути — пустая строка")
    void uri_null_returnsEmptyString() {

        // given / when / then
        assertThat(LogSanitizer.uri(null)).isEmpty();
    }

    @Test
    @DisplayName("Значение из внешних данных: переводы строк и табы заменяются, лог не ломается")
    void value_controlCharacters_areReplaced() {

        // given / when / then
        assertThat(LogSanitizer.value("image/png\r\nFAKE | ERROR | injected"))
                .isEqualTo("image/png__FAKE | ERROR | injected");
        assertThat(LogSanitizer.value("a\tb")).isEqualTo("a_b");
    }

    @Test
    @DisplayName("Значение из внешних данных: длинное значение обрезается")
    void value_longValue_isTruncated() {

        // given
        // when
        String value = LogSanitizer.value("x".repeat(500));

        // then
        assertThat(value).hasSize(121).endsWith("…");
    }

    @Test
    @DisplayName("Значение из внешних данных: обычное значение и null не меняются")
    void value_regularOrNullInput_returnsUnchangedOrEmpty() {

        // given / when / then
        assertThat(LogSanitizer.value("application/pdf")).isEqualTo("application/pdf");
        assertThat(LogSanitizer.value(null)).isEmpty();
    }

    @Test
    @DisplayName("Email маскируется: видна первая буква и домен")
    void email_validAddress_masksFirstLetterAndDomain() {

        // given / when / then
        assertThat(LogSanitizer.email("john.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(LogSanitizer.email("admin@corp.example.org")).isEqualTo("a***@corp.example.org");
    }

    @Test
    @DisplayName("Email: null, пустой и не разобранный адрес не утекают в лог")
    void email_blankOrUnparsable_returnsMaskedOrEmpty() {

        // given / when / then
        assertThat(LogSanitizer.email(null)).isEmpty();
        assertThat(LogSanitizer.email("  ")).isEmpty();
        assertThat(LogSanitizer.email("not-an-email")).isEqualTo("***");
    }

    @Test
    @DisplayName("Email в произвольном тексте маскируется, остальной текст сохраняется")
    void emails_addressesInText_masksButKeepsText() {

        // given
        String message = "Не найден пользователь с email: john.doe@example.com (портал support@corp.example.com)";

        // when / then
        assertThat(LogSanitizer.emails(message))
                .isEqualTo("Не найден пользователь с email: j***@example.com (портал s***@corp.example.com)");
        assertThat(LogSanitizer.emails("без адресов")).isEqualTo("без адресов");
        assertThat(LogSanitizer.emails(null)).isEmpty();
    }

    @Test
    @DisplayName("Имя файла: базовое имя скрыто, расширение остаётся")
    void fileName_variousInputs_masksBaseNameKeepsExtension() {

        // given / when / then
        assertThat(LogSanitizer.fileName("скан-паспорта.pdf")).isEqualTo("***.pdf");
        assertThat(LogSanitizer.fileName("report.final.xlsx")).isEqualTo("***.xlsx");
        assertThat(LogSanitizer.fileName("без-расширения")).isEqualTo("***");
        assertThat(LogSanitizer.fileName(".env")).isEqualTo("***");
        assertThat(LogSanitizer.fileName(null)).isEmpty();
    }

    @Test
    @DisplayName("Телефон: видны первые 4 символа")
    void phone_variousInputs_masksVisiblePrefix() {

        // given / when / then
        assertThat(LogSanitizer.phone("+79991234567")).isEqualTo("+799***");
        assertThat(LogSanitizer.phone("12")).isEqualTo("***");
        assertThat(LogSanitizer.phone(null)).isEmpty();
    }
}

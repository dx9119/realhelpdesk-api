package com.ukhanov.realhelpdesk.core.config;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Prod-preflight: старт рабочего контура валится на отладочной конфигурации")
class ProdPreflightValidatorTest {

    /** Хватает и для 32-байтного минимума HMAC-SHA256. */
    private static final String VALID_CAPTCHA_SECRET = "secure-captcha-hmac-secret-0123456789abcdef";

    @TempDir
    Path tempDir;

    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        // Безопасная база: каждый тест подменяет один ключ
        env = new MockEnvironment().withProperty("captcha.enabled", "true").withProperty("captcha.hmac-secret", VALID_CAPTCHA_SECRET)
                .withProperty("jwt.cookie.same-site", "Lax").withProperty("jwt.cookie.domain", "")
                .withProperty("cors.allowed-origins", "https://example.com").withProperty("spring.liquibase.enabled", "true")
                .withProperty("server.ssl.key-alias", "tomcat").withProperty("server.ssl.key-store-password", "changeit")
                .withProperty("server.ssl.key-store-type", "PKCS12").withProperty("spring.mail.host", "smtp.example.com")
                .withProperty("spring.mail.port", "587").withProperty("spring.mail.username", "user@example.com")
                .withProperty("spring.mail.properties.mail.smtp.auth", "true").withProperty("email.from", "noreply@example.com");
    }

    @Test
    @DisplayName("Безопасная конфигурация: preflight проходит")
    void validate_secureConfiguration_passes() {

        // given
        // when
        env.withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatCode(() -> new ProdPreflightValidator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Выключенная капча — старт валится")
    void validate_captchaDisabled_throwsIllegalState() {

        // given
        // when
        env.withProperty("captcha.enabled", "false").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captcha.enabled=false");
    }

    @Test
    @DisplayName("Не заданный HMAC-секрет капчи — старт валится")
    void validate_captchaSecretMissing_throwsIllegalState() {

        // given
        // when
        env.withProperty("captcha.hmac-secret", "").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTCHA_HMAC_SECRET");
    }

    @Test
    @DisplayName("HMAC-секрет капчи короче 32 байт — старт валится")
    void validate_captchaSecretTooShort_throwsIllegalState() {

        // given
        // when
        env.withProperty("captcha.hmac-secret", "short-secret").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("короче 32 байт");
    }

    @Test
    @DisplayName("Dev-значение HMAC-секрета капчи (dev-only…) — старт валится")
    void validate_captchaSecretDevValue_throwsIllegalState() {

        // given
        // when
        env.withProperty("captcha.hmac-secret", "dev-only-local-captcha-hmac-secret-0123456789").withProperty("server.ssl.key-store",
                "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-значение");
    }

    @Test
    @DisplayName("SameSite=None без домена cookie — старт валится")
    void validate_sameSiteNoneWithoutDomain_throwsIllegalState() {

        // given
        // when
        env.withProperty("jwt.cookie.same-site", "None").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.cookie.same-site=None требует jwt.cookie.domain");
    }

    @Test
    @DisplayName("localhost в CORS — старт валится")
    void validate_localhostInCors_throwsIllegalState() {

        // given
        // when
        env.withProperty("cors.allowed-origins", "https://localhost.ru,https://example.com").withProperty("server.ssl.key-store",
                "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("Отключённые миграции — старт валится")
    void validate_liquibaseDisabled_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.liquibase.enabled", "false").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.liquibase.enabled=false");
    }

    @Test
    @DisplayName("Контекст seed в миграциях — старт валится: рабочий контур нельзя наполнять фикстурами")
    void validate_seedMigrationContext_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.liquibase.contexts", "schema,seed").withProperty("server.ssl.key-store",
                "file:" + keystore("CN=example.com"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed");
    }

    @Test
    @DisplayName("Контекст schema (дефолт) — preflight проходит")
    void validate_schemaMigrationContext_passes() {

        // given
        // when
        env.withProperty("spring.liquibase.contexts", "schema").withProperty("server.ssl.key-store", "file:" + keystore("CN=example.com"));

        // then
        assertThatCode(() -> new ProdPreflightValidator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Самоподписанный keystore CN=localhost — старт валится")
    void validate_localhostKeystore_throwsIllegalState() {

        // given
        // when
        env.withProperty("server.ssl.key-store", "file:" + keystore("CN=localhost"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CN=localhost");
    }

    @Test
    @DisplayName("Все нарушения перечислены в одном исключении")
    void validate_multipleViolations_reportsAllInOneException() {

        // given
        // when
        env.withProperty("captcha.enabled", "false").withProperty("jwt.cookie.same-site", "None")
                .withProperty("cors.allowed-origins", "http://localhost:3000").withProperty("spring.liquibase.enabled", "false")
                .withProperty("server.ssl.key-store", "file:" + keystore("CN=localhost"));

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captcha.enabled=false").hasMessageContaining("jwt.cookie.same-site=None")
                .hasMessageContaining("localhost:3000").hasMessageContaining("spring.liquibase.enabled=false")
                .hasMessageContaining("CN=localhost");
    }

    @Test
    @DisplayName("Не заданный MAIL_HOST — старт валится")
    void validate_mailHostMissing_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.mail.host", "");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    @DisplayName("Неразрешённая подстановка ${MAIL_HOST} вместо хоста — старт валится")
    void validate_mailHostPlaceholderUnresolved_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.mail.host", "${MAIL_HOST}");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    @DisplayName("Локальный SMTP dev-стека (MAIL_HOST=smtp) — старт валится")
    void validate_localMailHost_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.mail.host", "smtp");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("локальный SMTP");
    }

    @Test
    @DisplayName("MAIL_PORT не число в диапазоне 1–65535 — старт валится")
    void validate_invalidMailPort_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.mail.port", "70000");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PORT");
    }

    @Test
    @DisplayName("Не заданный MAIL_FROM — старт валится")
    void validate_mailFromMissing_throwsIllegalState() {

        // given
        // when
        env.withProperty("email.from", "");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM");
    }

    @Test
    @DisplayName("SMTP_AUTH=true без MAIL_USER — старт валится")
    void validate_smtpAuthWithoutUser_throwsIllegalState() {

        // given
        // when
        env.withProperty("spring.mail.username", "");

        // then
        assertThatThrownBy(() -> new ProdPreflightValidator(env).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_USER");
    }

    /** Самоподписанный PKCS12 тем же способом, что entrypoint контейнера (keytool есть в JDK тестов). */
    private String keystore(String distinguishedName) {
        String file = "ks-" + Math.abs(distinguishedName.hashCode()) + ".p12";
        Path path = tempDir.resolve(file);
        try {
            Process process = new ProcessBuilder("keytool", "-genkeypair", "-alias", "tomcat", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", "changeit", "-keypass", "changeit", "-dname",
                    distinguishedName, "-validity", "30").redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IllegalStateException("keytool не создал keystore (код " + exitCode + "): " + output);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return path.toString();
    }
}

package com.ukhanov.realhelpdesk.core.config;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prod-preflight: при профиле {@code prod} старт валится, если рабочий контур собран с отладочными значениями — выключенная капча,
 * {@code SameSite=None} без домена cookie, {@code localhost} в CORS, отключённые миграции или их seed-контекст, самоподписанный keystore
 * {@code CN=localhost} или почта, наведённая на локальный SMTP dev-стека. Приём повторяет закрепление {@code ddl-auto=validate}: опасная
 * конфигурация не может «тихо» доехать до прода.
 *
 * <p>
 * Проверки читают финальный {@link Environment} (env-файлы + profile-файлы), то есть ловят и забытый override, и подмену в
 * {@code infrastructure/.env}. В dev-профиле компонент не создаётся.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Profile("prod")
@Component
public class ProdPreflightValidator {

    private static final String LOCALHOST = "localhost";

    /** Имя контейнера smtp4dev в dev-сети compose (MAIL_HOST=smtp): рабочему контуру нужен внешний почтовый сервер. */
    private static final String LOCAL_SMTP_SERVICE = "smtp";

    /** Незаданная env-подстановка остаётся в Environment строкой вида {@code ${MAIL_HOST}} — отправлять письма ей нельзя. */
    private static final String UNRESOLVED_PLACEHOLDER = "${";

    /** Префикс dev-значения капчи-секрета из .env.example: в рабочем контуре такое значение — дыра. */
    static final String DEV_SECRET_PREFIX = "dev-only";

    private final Environment env;

    @PostConstruct
    void validate() {
        List<String> violations = new ArrayList<>();

        if (!"true".equalsIgnoreCase(env.getProperty("captcha.enabled"))) {
            violations.add("captcha.enabled=false — в рабочем контуре капча должна быть включена");
        }
        checkCaptchaSecret(violations);

        String sameSite = env.getProperty("jwt.cookie.same-site", "");
        String cookieDomain = env.getProperty("jwt.cookie.domain", "");
        if ("none".equalsIgnoreCase(sameSite) && cookieDomain.isBlank()) {
            violations.add("jwt.cookie.same-site=None требует jwt.cookie.domain — браузеры отвергают SameSite=None без домена cookie");
        }

        String corsOrigins = env.getProperty("cors.allowed-origins", "");
        if (corsOrigins.toLowerCase().contains("localhost") || corsOrigins.contains("127.0.0.1")) {
            violations.add("cors.allowed-origins содержит localhost/127.0.0.1: «продориgin» в рабочем контуре недопустим — " + corsOrigins);
        }

        if (!"true".equalsIgnoreCase(env.getProperty("spring.liquibase.enabled"))) {
            violations.add("spring.liquibase.enabled=false — миграции схемы в рабочем контуре обязательны");
        }
        checkMigrationContexts(violations);

        checkMail(violations);

        checkKeystoreCommonName(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Prod-preflight: рабочий контур запущен с небезопасной конфигурацией:\n - " + String.join("\n - ", violations));
        }
        logger.info("Prod-preflight пройден: капча включена, миграции обязательны, keystore не самоподписанный, почта на внешнем SMTP");
    }

    /**
     * Контексты Liquibase: в рабочем контуре недопустим {@code seed} — он наполняет базу демо-пользователями и заявками из
     * {@code db/changelog/seed}. Пустое значение безопасно: changeset'ы без контекста (весь DDL) выполняются, фикстуры — нет.
     */
    private void checkMigrationContexts(List<String> violations) {
        String contexts = property("spring.liquibase.contexts");
        boolean seedRequested = List.of(contexts.split(",")).stream().map(String::trim).anyMatch("seed"::equalsIgnoreCase);
        if (seedRequested) {
            violations.add("spring.liquibase.contexts содержит 'seed' — рабочие данные нельзя подменять фикстурами (DB_MIGRATIONS_CONTEXTS="
                    + contexts + ")");
        }
    }

    /**
     * Почта рабочего контура: хост задан и не указывает на локальный SMTP dev-стека, порт — число 1–65535, отправитель похож на адрес, а
     * при включённой аутентификации задан и пользователь. Незаданный {@code MAIL_*} валит чтение свойства плейсхолдером —
     * {@link #property(String)} превращает это в пустое значение, чтобы ошибка попала в список нарушений, а не упала исключением.
     */
    private void checkMail(List<String> violations) {
        String host = property("spring.mail.host");
        if (isUnset(host)) {
            violations.add("spring.mail.host (MAIL_HOST) не задан — письма отправляться не будут");
        } else if (LOCALHOST.equalsIgnoreCase(host) || host.contains("127.0.0.1") || LOCAL_SMTP_SERVICE.equalsIgnoreCase(host)) {
            violations.add("spring.mail.host=" + host + " — это локальный SMTP dev-стека, рабочему контуру нужен внешний почтовый сервер");
        }

        String port = property("spring.mail.port");
        if (isUnset(port) || !isValidPort(port)) {
            violations.add("spring.mail.port (MAIL_PORT) должен быть числом 1–65535, сейчас: " + port);
        }

        String from = property("email.from");
        if (isUnset(from) || !from.contains("@")) {
            violations.add("email.from (MAIL_FROM) не задан или не похож на адрес, отправитель писем обязателен: " + from);
        }

        if ("true".equalsIgnoreCase(property("spring.mail.properties.mail.smtp.auth")) && property("spring.mail.username").isBlank()) {
            violations.add("SMTP_AUTH=true, но spring.mail.username (MAIL_USER) пуст — аутентификация на SMTP упадёт");
        }
    }

    /**
     * HMAC-секрет подписи PoW-токенов: задан, не короче 32 байт (минимум HMAC-SHA256) и не является dev-значением из инструкций локального
     * запуска — иначе подпись капчи можно подделать, предъявив свой токен.
     */
    private void checkCaptchaSecret(List<String> violations) {
        String secret = property("captcha.hmac-secret");
        if (isUnset(secret)) {
            violations.add("captcha.hmac-secret (CAPTCHA_HMAC_SECRET) не задан — подпись PoW-токенов капчи невозможна");
        } else if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            violations.add("captcha.hmac-secret короче 32 байт — секрет подписи капчи слишком слабый");
        } else if (secret.startsWith(DEV_SECRET_PREFIX)) {
            violations.add(
                    "captcha.hmac-secret содержит dev-значение (" + DEV_SECRET_PREFIX + "…) — сгенерируйте свой: openssl rand -base64 32");
        }
    }

    /** Читает свойство, подавляя {@link PlaceholderResolutionException}: незаданная env-подстановка читается как пустое значение. */
    private String property(String key) {
        try {
            return env.getProperty(key, "");
        } catch (PlaceholderResolutionException e) {
            return "";
        }
    }

    private boolean isUnset(String value) {
        return value.isBlank() || value.contains(UNRESOLVED_PLACEHOLDER);
    }

    private boolean isValidPort(String port) {
        try {
            int number = Integer.parseInt(port.trim());
            return number >= 1 && number <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Самоподписанный keystore {@code CN=localhost} генерирует entrypoint для отладки — в проде он недопустим. */
    private void checkKeystoreCommonName(List<String> violations) {
        String location = env.getProperty("server.ssl.key-store", "");
        String alias = env.getProperty("server.ssl.key-alias", "");
        String password = env.getProperty("server.ssl.key-store-password", "");
        String type = env.getProperty("server.ssl.key-store-type", "PKCS12");

        if (location.isBlank() || alias.isBlank() || password.isBlank()) {
            violations.add("server.ssl.key-store/key-alias/key-store-password не заданы — TLS не настроен");
            return;
        }

        try {
            Resource resource = new DefaultResourceLoader().getResource(location);
            try (InputStream in = resource.getInputStream()) {
                KeyStore keyStore = KeyStore.getInstance(type);
                keyStore.load(in, password.toCharArray());

                if (!keyStore.containsAlias(alias)) {
                    violations.add("в keystore " + location + " нет алиаса '" + alias + "'");
                    return;
                }

                String commonName = commonNameOf(keyStore.getCertificate(alias));
                if (LOCALHOST.equalsIgnoreCase(commonName)) {
                    violations.add(
                            "keystore " + location + " выпущен на CN=localhost (самоподпись entrypoint) — смонтируйте реальный сертификат");
                }
            }
        } catch (Exception e) {
            violations.add("keystore " + location + " не читается: " + e.getMessage());
        }
    }

    private String commonNameOf(java.security.cert.Certificate certificate) throws Exception {
        if (!(certificate instanceof X509Certificate x509)) {
            return null;
        }
        LdapName subject = new LdapName(x509.getSubjectX500Principal().getName());
        for (Rdn rdn : subject.getRdns()) {
            if ("CN".equalsIgnoreCase(rdn.getType())) {
                return String.valueOf(rdn.getValue());
            }
        }
        return null;
    }
}

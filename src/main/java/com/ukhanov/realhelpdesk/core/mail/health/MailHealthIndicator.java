package com.ukhanov.realhelpdesk.core.mail.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.mail.MessagingException;

import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Доступность SMTP: рукопожатие с сервером через {@link JavaMailSenderImpl#testConnection()} — письмо при проверке не
 * отправляется.
 *
 * <p>
 * Сознательно НЕ входит в readiness: недоступная почта не должна останавливать трафик — заявки и ответы работают без
 * писем, уведомления подхватятся после восстановления. Индикатор вынесен в отдельную health-группу {@code smtp} (Boot 4
 * запрещает совпадение имени группы с именем contributor'а, поэтому группа называется smtp, а блок в деталях — mail) и
 * выключается свойством {@code management.health.mail.enabled=false}. Таймауты соединения заданы в
 * {@code spring.mail.properties.mail.smtp.*}, результат кэшируется на несколько секунд — опрос health не должен держать
 * соединения с SMTP.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnEnabledHealthIndicator("mail")
public class MailHealthIndicator implements HealthIndicator {

    /** Имя бина в actuator: mail → /actuator/health/mail показывает блок "mail". */
    public static final String NAME = "mail";

    /** Свежий результат держим 5 секунд: проверка SMTP — сетевая операция, опрашивать её на каждый scrape нельзя. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final JavaMailSender mailSender;

    private volatile Health cachedHealth;
    private volatile Instant checkedAt;

    public MailHealthIndicator(JavaMailSender mailSender) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender must not be null");
    }

    @Override
    public Health health() {
        Health cached = cachedHealth;
        if (cached != null && checkedAt.plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        Health health = doCheck();
        cachedHealth = health;
        checkedAt = Instant.now();
        return health;
    }

    private Health doCheck() {
        if (!(mailSender instanceof JavaMailSenderImpl impl)) {
            // не-стандартный отправитель (тестовая заглушка): проверять нечем, блок не участвует
            return Health.up().withDetail("state", "skipped").withDetail("reason", "not a JavaMailSenderImpl").build();
        }
        try {
            try {
                impl.testConnection();
            } catch (MessagingException e) {
                throw new MailException(e.getMessage(), e) {
                };
            }
            return Health.up().withDetail("host", impl.getHost()).withDetail("port", impl.getPort()).build();
        } catch (MailException e) {
            logger.warn("SMTP недоступен для health-проверки: {}", e.getMessage());
            return Health.down(e).withDetail("host", impl.getHost()).withDetail("port", impl.getPort()).build();
        }
    }
}

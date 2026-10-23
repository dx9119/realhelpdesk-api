package com.ukhanov.realhelpdesk.core.security.user;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.config.OperatorBootstrapProperties;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap операторов: на старте повышает до {@code ROLE_OPERATOR} пользователей из {@code operator.bootstrap.emails}.
 * Роль читается приложением из БД на каждом запросе, поэтому повышение действует сразу.
 *
 * <p>
 * Безопасность механизма:
 * </p>
 * <ul>
 * <li>пользователь должен уже существовать и иметь подтверждённый email — список не создаёт аккаунты и не даёт прав по
 * email, который кто-то может зарегистрировать позже;</li>
 * <li>идемпотентно: уже оператор пропускается, повторный старт ничего не меняет;</li>
 * <li>только повышение: вывод адреса из списка роль не снимает — исключён риск лок-аута из-за опечатки в конфиге;</li>
 * <li>в лог идёт id пользователя и замаскированный email, а не адрес целиком.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorBootstrapRunner implements ApplicationRunner {

    private final OperatorBootstrapProperties operatorBootstrapProperties;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<String> emails = normalize(operatorBootstrapProperties.getEmails());
        if (emails.isEmpty()) {
            logger.debug("Оператор-бутстрап: список operator.bootstrap.emails пуст — повышений нет");
            return;
        }
        emails.forEach(this::promote);
    }

    private void promote(String email) {
        Optional<UserModel> found = userRepository.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            logger.warn("Оператор-бутстрап: пользователь {} не зарегистрирован — пропуск", LogSanitizer.email(email));
            return;
        }
        UserModel user = found.get();
        if (!user.isEmailVerified()) {
            logger.warn("Оператор-бутстрап: email пользователя id={} не подтверждён — пропуск", user.getId());
            return;
        }
        if (user.getUserRole() == UserRole.ROLE_OPERATOR) {
            logger.debug("Оператор-бутстрап: у пользователя id={} уже ROLE_OPERATOR", user.getId());
            return;
        }
        user.setUserRole(UserRole.ROLE_OPERATOR);
        userRepository.save(user);
        logger.info("Оператор-бутстрап: пользователю id={} назначена роль ROLE_OPERATOR (email {})", user.getId(),
                LogSanitizer.email(email));
    }

    private static List<String> normalize(List<String> emails) {
        if (emails == null) {
            return List.of();
        }
        return emails.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}

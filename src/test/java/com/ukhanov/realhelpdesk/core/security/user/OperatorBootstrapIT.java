package com.ukhanov.realhelpdesk.core.security.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ukhanov.realhelpdesk.AbstractIntegrationTest;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что при старте контекста {@link OperatorBootstrapRunner} повышает пользователей из
 * {@code operator.bootstrap.emails}. Пользователи создаются отдельным раннером с более высоким приоритетом — до
 * бутстрапа.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=test")
@DisplayName("Оператор-бутстрап на старте контекста")
class OperatorBootstrapIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void operatorBootstrapEmails(DynamicPropertyRegistry registry) {
        registry.add("operator.bootstrap.emails",
                () -> "verified-operator@example.com, VERIFIED-Operator2@example.com, "
                        + "missing-operator@example.com, unverified-operator@example.com, "
                        + "verified-operator@example.com");
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Подтверждённые из списка становятся ROLE_OPERATOR (независимо от регистра), "
            + "отсутствующие и неподтверждённые — нет")
    void operatorBootstrap_mixedUserStates_promotesOnlyVerified() {
        // given / when / then
        assertThat(userRepository.findByEmailIgnoreCase("verified-operator@example.com"))
                .hasValueSatisfying(user -> assertThat(user.getUserRole()).isEqualTo(UserRole.ROLE_OPERATOR));
        assertThat(userRepository.findByEmailIgnoreCase("VERIFIED-Operator2@example.com"))
                .as("регистр email в списке не важен")
                .hasValueSatisfying(user -> assertThat(user.getUserRole()).isEqualTo(UserRole.ROLE_OPERATOR));
        assertThat(userRepository.findByEmailIgnoreCase("unverified-operator@example.com"))
                .as("без подтверждённого email роль не меняется")
                .hasValueSatisfying(user -> assertThat(user.getUserRole()).isEqualTo(UserRole.ROLE_USER));
        assertThat(userRepository.findByEmailIgnoreCase("missing-operator@example.com"))
                .as("несуществующий аккаунт не создаётся")
                .isEmpty();
    }

    @TestConfiguration
    static class SeedUsers {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner seed(UserRepository repository) {
            return args -> {
                repository.save(user("verified-operator@example.com", true));
                repository.save(user("VERIFIED-Operator2@example.com", true));
                repository.save(user("unverified-operator@example.com", false));
            };
        }

        private static UserModel user(String email, boolean verified) {
            UserModel user = new UserModel();
            user.setFirstName("Тест");
            user.setLastName("Тестов");
            user.setEmail(email);
            user.setPasswordHash("hash");
            user.setEmailVerified(verified);
            return user;
        }
    }
}

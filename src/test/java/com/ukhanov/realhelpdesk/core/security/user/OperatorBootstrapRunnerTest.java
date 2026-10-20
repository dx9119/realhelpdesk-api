package com.ukhanov.realhelpdesk.core.security.user;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.core.config.OperatorBootstrapProperties;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Оператор-бутстрап: повышение пользователей из списка при старте")
class OperatorBootstrapRunnerTest {

    private static final String EMAIL = "operator@example.com";

    @Mock
    private UserRepository userRepository;

    private OperatorBootstrapProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OperatorBootstrapProperties();
    }

    @Test
    @DisplayName("Существующий подтверждённый пользователь повышается до ROLE_OPERATOR")
    void run_verifiedUser_promotesToOperator() {

        // given
        // when
        UserModel user = user(UserRole.ROLE_USER, true);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        runner(EMAIL).run(null);

        // then
        assertThat(user.getUserRole()).isEqualTo(UserRole.ROLE_OPERATOR);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("Пользователь без подтверждённого email не повышается")
    void run_unverifiedUser_skipsPromotion() {

        // given
        // when
        UserModel user = user(UserRole.ROLE_USER, false);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        runner(EMAIL).run(null);

        // then
        assertThat(user.getUserRole()).isEqualTo(UserRole.ROLE_USER);
        verify(userRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Незарегистрированный email пропускается — аккаунты не создаются")
    void run_missingUser_skipsSave() {

        // given
        // when
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        runner(EMAIL).run(null);

        // then
        verify(userRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Уже оператор не пересохраняется (идемпотентность)")
    void run_alreadyOperator_skipsResave() {

        // given
        // when
        UserModel user = user(UserRole.ROLE_OPERATOR, true);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        runner(EMAIL).run(null);

        // then
        verify(userRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Пустой список — ни одного обращения к БД")
    void run_emptyList_makesNoRepositoryCalls() {

        // given
        // when
        runner().run(null);

        // then
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Список нормализуется: trim, нижний регистр, без пустых и дублей")
    void run_mixedCaseWithBlanks_normalizesEmails() {

        // given
        // when
        UserModel user = user(UserRole.ROLE_USER, true);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        runner("  Operator@Example.com ", "operator@example.com", "", "   ").run(null);

        // then
        verify(userRepository).findByEmailIgnoreCase(EMAIL);
        verify(userRepository).save(user);
    }

    private OperatorBootstrapRunner runner(String... emails) {
        properties.setEmails(List.of(emails));
        return new OperatorBootstrapRunner(properties, userRepository);
    }

    private UserModel user(UserRole role, boolean verified) {
        UserModel user = new UserModel();
        user.setEmail(EMAIL);
        user.setUserRole(role);
        user.setEmailVerified(verified);
        return user;
    }
}

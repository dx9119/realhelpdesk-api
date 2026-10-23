package com.ukhanov.realhelpdesk.core.security.user.service;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserDetailsProjection;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserDomainService {

    private final UserRepository userRepository;

    private UserModel resolveUserByIdentifier(String identifier, boolean isEmail) {
        Objects.requireNonNull(identifier, "Идентификатор не может быть null");

        if (isEmail) {
            return userRepository.findByEmail(identifier).orElseThrow(() -> {
                // адрес и код восстановления в лог не пишем — только сам факт поиска (audit B12)
                logger.debug("Пользователь не найден по email");
                return new UsernameNotFoundException("Не найден пользователь с email: " + identifier);
            });
        } else {
            Long id = Long.valueOf(identifier);
            return userRepository.findById(id).orElseThrow(() -> {
                logger.debug("Пользователь не найден по id={}", identifier);
                return new UsernameNotFoundException("Не найден пользователь с ID: " + identifier);
            });
        }

    }

    public UserModel getUserByEmail(String email) {
        return resolveUserByIdentifier(email, true);
    }

    /**
     * Поиск по email без исключения: нужен там, где отсутствие пользователя — штатный сценарий, а не ошибка. Вход через
     * Keycloak решает по результату, создавать ли учётную запись; {@link #getUserByEmail} в этом же месте бросал бы
     * исключение как способ управления потоком.
     */
    public Optional<UserModel> findUserByEmail(String email) {
        Objects.requireNonNull(email, "Email не может быть null");
        return userRepository.findByEmailIgnoreCase(email);
    }

    public UserModel getUserById(Long userId) {
        return resolveUserByIdentifier(userId.toString(), false);
    }

    public boolean isUserExistsByEmail(String email) {
        Objects.requireNonNull(email, "Email не может быть null");

        return userRepository.existsByEmail(email);
    }

    @Transactional
    public UserModel saveUser(UserModel user) {
        Objects.requireNonNull(user, "Пользователь не может быть null");

        return userRepository.save(user);
    }

    public UserDetailsProjection getUserDetailsById(Long userId) {
        Objects.requireNonNull(userId, "ID пользователя не может быть null");

        return userRepository.findProjectedById(userId).orElseThrow(() -> {
            logger.debug("Пользователь не найден по id={}", userId);
            return new UsernameNotFoundException("Не найден пользователь с ID: " + userId);
        });
    }

    /**
     * Батч-загрузка контактов по списку ID (один запрос вместо N): отсутствующие ID просто не попадают в карту —
     * вызывающий подставляет заглушку.
     */
    public Map<Long, UserDetailsProjection> getUserDetailsByIds(Collection<Long> userIds) {
        Objects.requireNonNull(userIds, "Список ID не может быть null");
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findProjectedByIdIn(userIds)
                .stream()
                .collect(Collectors.toMap(UserDetailsProjection::getId, user -> user, (first, second) -> first));
    }

    /** Батч-загрузка пользователей по списку ID: одна выборка вместо N find по одному. */
    public Map<Long, UserModel> getUsersByIds(Collection<Long> userIds) {
        Objects.requireNonNull(userIds, "Список ID не может быть null");
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findAllById(userIds)
                .stream()
                .collect(Collectors.toMap(UserModel::getId, user -> user, (first, second) -> first));
    }

    public UserModel getUserByRecoveryPasswdToken(Long code) {
        Objects.requireNonNull(code, "Код обязателен");

        return userRepository.findByRecoveryPasswdToken(code).orElseThrow(() -> {
            // Код восстановления не логируем и не подставляем в сообщение исключения —
            // оно уходит в WARN в GlobalExceptionHandler (audit B12)
            logger.debug("Пользователь не найден по recoveryToken");
            return new UsernameNotFoundException("Неизвестный код восстановления");
        });
    }

}

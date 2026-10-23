package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Поведение keycloak-профиля: как приложение относится к пользователям и ролям, которые приходят из токена Keycloak.
 *
 * <p>
 * Значения — литералы в {@code application-keycloak.properties}: ключи профиля не входят в схему
 * {@code application.properties}, поэтому окружение их не подставляет и обязательными они не являются. Переопределить
 * можно стандартным для Spring способом — переменной окружения {@code KEYCLOAK_SYNC_ROLES}.
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "keycloak")
@Getter
@Setter
public class KeycloakProperties {

    /**
     * Переносить realm-роли Keycloak в {@code UserRole} БД при каждом входе. Приложение читает роль из БД (как и в
     * локальном контуре), поэтому без синхронизации роль в Keycloak ни на что не влияет.
     */
    private boolean syncRoles = true;

    /**
     * Создавать пользователя БД при первом входе через Keycloak. Выключено — неизвестный email получает 401: аккаунт
     * должен существовать заранее (предзаполнение, миграция из другого контура).
     */
    private boolean autoCreateUsers = true;
}

package com.ukhanov.realhelpdesk.core.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Список email для назначения {@code ROLE_OPERATOR} при старте (bootstrap). Значение приходит из
 * {@code APP_OPERATOR_EMAILS} — через запятую. Пусто — повышений нет. Список только повышает роль; чтобы снять права,
 * роль нужно менять отдельно.
 */
@Component
@ConfigurationProperties(prefix = "operator.bootstrap")
@Getter
@Setter
public class OperatorBootstrapProperties {

    /** Email-адреса, которым при старте назначается {@code ROLE_OPERATOR}. */
    private List<String> emails = List.of();
}

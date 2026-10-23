package com.ukhanov.realhelpdesk.core.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Подключение к MinIO: адрес, учётные данные и бакет для файлов вложений заявок.
 *
 * <p>
 * Значения — только переменные окружения (см. application.properties): адрес сервиса и имя бакета — из
 * infrastructure/.env, учётки — из infrastructure/docker-compose.yaml (секреты infrastructure/.env). Файлы лежат в
 * MinIO, в БД хранятся только метаданные (attachment_model).
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "minio")
@Getter
@Setter
public class MinioProperties {

    /** Адрес API MinIO, например http://minio:9000 — имя сервиса из compose, не localhost. */
    private String endpoint;

    /** Доступ (MINIO_ROOT_USER / аналогичная учётная запись с правами на бакет). */
    private String accessKey;

    /** Секрет доступа, хранится в infrastructure/.env и подставляется через infrastructure/docker-compose.yaml. */
    private String secretKey;

    /** Бакет с файлами вложений; создаётся автоматически при первом обращении. */
    private String bucket;
}

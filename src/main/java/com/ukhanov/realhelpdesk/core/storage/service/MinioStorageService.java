package com.ukhanov.realhelpdesk.core.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.storage.config.MinioProperties;
import com.ukhanov.realhelpdesk.core.storage.exception.StorageException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import lombok.extern.slf4j.Slf4j;

/**
 * Хранилище файлов поверх MinIO: бакет создаётся автоматически, объекты адресуются ключом вида
 * {@code tickets/{ticketId}/{uuid}.{ext}}.
 *
 * <p>
 * Все ошибки SDK сворачиваются в {@link StorageException} (503, объект не найден — 404): клиент MinIO бросает пачку
 * checked-исключений, и раскладывать их по слоям выше смысла нет — это одна и та же «хранилище не ответило».
 * </p>
 */
@Slf4j
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final ObservationRegistry observationRegistry;

    /**
     * После успешной проверки бакет не перепроверяется на каждой операции; при сбое флаг не поднимается и проверка
     * повторится.
     */
    private volatile boolean bucketReady;
    /**
     * Идемпотентная подготовка бакета: создаёт его при отсутствии. Вызывается на старте (StorageBucketInitializer) и
     * перед каждой операцией, пока не пройдёт успешно.
     */

    public MinioStorageService(MinioClient minioClient, MinioProperties properties,
            ObservationRegistry observationRegistry) {
        this.minioClient = Objects.requireNonNull(minioClient, "minioClient не должен быть null");
        this.properties = Objects.requireNonNull(properties, "Настройки MinIO не должны быть null");
        this.observationRegistry = Objects.requireNonNull(observationRegistry,
                "observationRegistry не должен быть null");
    }

    public void ensureBucket() {
        if (bucketReady) {
            return;
        }
        String bucket = properties.getBucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                logger.info("Создан бакет хранилища файлов: {}", bucket);
            }
            bucketReady = true;
        } catch (ErrorResponseException | InsufficientDataException | InternalException | InvalidResponseException
                | InvalidKeyException | IOException | NoSuchAlgorithmException | ServerException
                | XmlParserException e) {
            throw new StorageException("Хранилище файлов недоступно, бакет " + bucket + " не подготовлен", e);
        }
    }

    /** Потоковая запись: файл не держится в памяти приложения целиком. */
    public void upload(String objectKey, InputStream content, long size, String contentType) {
        Objects.requireNonNull(objectKey, "Ключ объекта не должен быть null");
        Objects.requireNonNull(content, "Содержимое файла не должно быть null");
        if (size <= 0) {
            throw new IllegalArgumentException("Размер файла должен быть положительным: " + objectKey);
        }
        Observation observation = Observation.createNotStarted("attachment.upload", observationRegistry)
                .lowCardinalityKeyValue("operation", "upload")
                .highCardinalityKeyValue("attachment.object.key", objectKey)
                .highCardinalityKeyValue("attachment.size.bytes", String.valueOf(size))
                .start();
        try {
            ensureBucket();
            // partSize = -1: размер известен, а размер части MinIO посчитает сам (5MiB — его минимум, файл меньше
            // лимита — одна часть).
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .build());
            logger.debug("Файл сохранён в хранилище: {}", objectKey);
            observation.stop();
        } catch (ErrorResponseException | InsufficientDataException | InternalException | InvalidResponseException
                | InvalidKeyException | IOException | NoSuchAlgorithmException | ServerException | XmlParserException
                | StorageException e) {
            observation.lowCardinalityKeyValue("outcome", "error").stop();
            throw new StorageException("Не удалось сохранить файл в хранилище", e);
        }
    }

    /**
     * Открывает поток чтения целиком. Владение потоком — на вызывающем: закрыть его обязательно, иначе соединение с
     * MinIO повиснет. Обращение к бакету, которого ещё нет, — это не 404, а «хранилище не готово» (503), поэтому
     * ensureBucket вызывается и здесь.
     */
    public InputStream download(String objectKey) {
        return download(objectKey, 0, -1);
    }

    /**
     * Открывает поток чтения фрагмента {@code [offset, offset + length)} — MinIO отдаёт только запрошенные байты (HTTP
     * Range к объекту), поэтому на нестабильной сети фрагмент не тянет за собой весь файл. {@code length < 0} — до
     * конца объекта. Владение потоком — на вызывающем.
     */
    public InputStream download(String objectKey, long offset, long length) {
        Objects.requireNonNull(objectKey, "Ключ объекта не должен быть null");
        if (offset < 0) {
            throw new IllegalArgumentException("Смещение фрагмента не может быть отрицательным: " + offset);
        }
        Observation observation = Observation.createNotStarted("attachment.download", observationRegistry)
                .lowCardinalityKeyValue("operation", "download")
                .highCardinalityKeyValue("attachment.object.key", objectKey)
                .start();
        try {
            ensureBucket();
            GetObjectArgs.Builder builder = GetObjectArgs.builder().bucket(properties.getBucket()).object(objectKey);
            if (offset > 0) {
                builder.offset(offset);
            }
            if (length >= 0) {
                builder.length(length);
            }
            InputStream stream = minioClient.getObject(builder.build());
            observation.stop();
            return stream;
        } catch (ErrorResponseException e) {
            observation.lowCardinalityKeyValue("outcome", "not_found").stop();
            if (e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code())) {
                throw StorageException.notFound("Файл не найден в хранилище");
            }
            throw new StorageException("Не удалось получить файл из хранилища", e);
        } catch (InsufficientDataException | InternalException | InvalidResponseException | InvalidKeyException
                | IOException | NoSuchAlgorithmException | ServerException | XmlParserException | StorageException e) {
            observation.lowCardinalityKeyValue("outcome", "error").stop();
            throw new StorageException("Не удалось получить файл из хранилища", e);
        }
    }

    /**
     * Best-effort удаление: нужно для отката неудачной загрузки, поэтому ошибки только логируются — падать из-за уже
     * неудачной операции нельзя.
     */
    public void delete(String objectKey) {
        if (objectKey == null) {
            return;
        }
        Observation observation = Observation.createNotStarted("attachment.delete", observationRegistry)
                .lowCardinalityKeyValue("operation", "delete")
                .highCardinalityKeyValue("attachment.object.key", objectKey)
                .start();
        try {
            minioClient
                    .removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket()).object(objectKey).build());
            logger.debug("Файл удалён из хранилища: {}", objectKey);
            observation.stop();
        } catch (ErrorResponseException | InsufficientDataException | InternalException | InvalidResponseException
                | InvalidKeyException | IOException | NoSuchAlgorithmException | ServerException
                | XmlParserException e) {
            observation.lowCardinalityKeyValue("outcome", "error").stop();
            logger.warn("Не удалось удалить файл из хранилища {}: {}", objectKey, e.getMessage());
        }
    }
}

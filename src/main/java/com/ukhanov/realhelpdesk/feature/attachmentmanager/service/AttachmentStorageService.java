package com.ukhanov.realhelpdesk.feature.attachmentmanager.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.storage.service.MinioStorageService;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.exception.AttachmentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Физический слой вложений: имя/MIME-тип, ключ объекта и операции MinIO. Не знает ни о БД, ни о сообщениях заявки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentStorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final String DEFAULT_FILE_NAME = "file";
    private static final String STORAGE_FOLDER = "tickets";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String CONTENT_TYPE_PATTERN = "[\\w!#$&^_.+-]+/[\\w!#$&^_.+-]+";

    private final MinioStorageService storageService;

    /** Результат загрузки файла в хранилище: метаданные, которые дальше идут в БД и ответ API. */
    public record StoredAttachment(String fileName, String contentType, long sizeBytes, String storageKey) {
    }

    public StoredAttachment upload(MultipartFile file, Long ticketId) throws AttachmentException {
        Objects.requireNonNull(file, "Файл не должен быть null");
        Objects.requireNonNull(ticketId, "ID заявки не должен быть null");
        if (file.isEmpty()) {
            throw AttachmentException.badRequest("Файл не может быть пустым");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String contentType = resolveContentType(file.getContentType());
        long sizeBytes = file.getSize();
        String storageKey = storageKey(ticketId, fileName);
        try (InputStream content = file.getInputStream()) {
            storageService.upload(storageKey, content, sizeBytes, contentType);
        } catch (IOException e) {
            logger.debug("Не удалось прочитать загружаемый файл «{}» для заявки {}", LogSanitizer.fileName(fileName),
                    ticketId, e);
            throw new AttachmentException("Не удалось прочитать файл", HttpStatus.BAD_REQUEST, e);
        }
        return new StoredAttachment(fileName, contentType, sizeBytes, storageKey);
    }

    public InputStream download(String storageKey) {
        return storageService.download(storageKey);
    }

    public InputStream download(String storageKey, long start, long length) {
        return storageService.download(storageKey, start, length);
    }

    public void delete(String storageKey) {
        storageService.delete(storageKey);
    }

    /**
     * Оставляет только имя: браузер шлёт C:\\fakepath\\..., а длинное имя урезается с начала, чтобы сохранить
     * расширение.
     */
    private String sanitizeFileName(String originalFileName) {
        String name = originalFileName == null ? "" : originalFileName.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = DEFAULT_FILE_NAME;
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILE_NAME_LENGTH);
        }
        return name;
    }

    private String resolveContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.length() > MAX_FILE_NAME_LENGTH
                || !contentType.trim().matches(CONTENT_TYPE_PATTERN)) {
            return DEFAULT_CONTENT_TYPE;
        }
        return contentType.trim();
    }

    private String storageKey(Long ticketId, String fileName) {
        return STORAGE_FOLDER + "/" + ticketId + "/" + UUID.randomUUID() + extensionOf(fileName);
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,15}") ? extension : "";
    }
}

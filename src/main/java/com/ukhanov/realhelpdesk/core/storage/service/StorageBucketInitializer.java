package com.ukhanov.realhelpdesk.core.storage.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.storage.exception.StorageException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Готовит бакет к работе при старте приложения.
 *
 * <p>
 * Ошибка здесь не валит приложение: хранилище может быть временно недоступно, а приложение без файлов всё равно
 * работает (заявки, сообщения). Незавершённая подготовка повторится при первой загрузке — см.
 * {@link MinioStorageService#ensureBucket()}.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class StorageBucketInitializer implements ApplicationRunner {

    private final MinioStorageService storageService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            storageService.ensureBucket();
            logger.info("Хранилище файлов готово к работе");
        } catch (StorageException e) {
            logger.error("Не удалось подготовить бакет хранилища на старте: {}. Приложение продолжит работу, "
                    + "подготовка повторится при первой загрузке файла.", e.getMessage());
        }
    }
}

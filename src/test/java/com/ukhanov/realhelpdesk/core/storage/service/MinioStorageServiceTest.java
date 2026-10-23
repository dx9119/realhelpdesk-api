package com.ukhanov.realhelpdesk.core.storage.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.core.storage.config.MinioProperties;
import com.ukhanov.realhelpdesk.core.storage.exception.StorageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты хранилища файлов MinioStorageService")
class MinioStorageServiceTest {

    private static final String BUCKET = "realhelpdesk-attachments";
    private static final String OBJECT_KEY = "tickets/42/2f0b0e8e-1f1a-4b0a-9b1a-000000000000.pdf";
    private static final byte[] CONTENT = "file".getBytes(StandardCharsets.UTF_8);

    @Mock
    private MinioClient mockMinioClient;

    @Captor
    private ArgumentCaptor<BucketExistsArgs> bucketCaptor;
    @Captor
    private ArgumentCaptor<PutObjectArgs> putCaptor;
    @Captor
    private ArgumentCaptor<GetObjectArgs> getCaptor;
    @Captor
    private ArgumentCaptor<RemoveObjectArgs> removeCaptor;

    private MinioStorageService storageService;

    @BeforeEach
    void setUp() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setAccessKey("minioadmin");
        properties.setSecretKey("secret");
        properties.setBucket(BUCKET);
        storageService = new MinioStorageService(mockMinioClient, properties, ObservationRegistry.NOOP);
    }

    // ────────────────────────────────────────────────
    // upload
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("upload → бакет существует → объект залит в него же")
    void upload_existingBucket_putsObjectIntoBucket() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        storageService.upload(OBJECT_KEY, new ByteArrayInputStream(CONTENT), CONTENT.length, "application/pdf");

        // then
        verify(mockMinioClient).putObject(putCaptor.capture());
        PutObjectArgs args = putCaptor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo(OBJECT_KEY);
        assertThat(args.objectSize()).isEqualTo(CONTENT.length);
        verify(mockMinioClient, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("upload → бакета нет → сначала создаётся бакет, затем объект")
    void upload_missingBucket_createsBucketFirst() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        storageService.upload(OBJECT_KEY, new ByteArrayInputStream(CONTENT), CONTENT.length, "text/plain");

        // then
        verify(mockMinioClient).makeBucket(any(MakeBucketArgs.class));
        verify(mockMinioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("upload → MinIO недоступен → StorageException 503, IOException наверх не уходит")
    void upload_clientFailure_wrapsIntoStorageException() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        doThrow(new IOException("connection refused")).when(mockMinioClient).putObject(any(PutObjectArgs.class));

        // then
        assertThatThrownBy(() -> storageService.upload(OBJECT_KEY, new ByteArrayInputStream(CONTENT), CONTENT.length,
                "text/plain"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Не удалось сохранить файл");
    }

    @Test
    @DisplayName("upload → неположительный размер → IllegalArgumentException, в MinIO не ходим")
    void upload_nonPositiveSize_throwsIllegalArgumentException() throws Exception {

        // given
        // when
        ByteArrayInputStream emptyContent = new ByteArrayInputStream(new byte[0]);

        // then
        assertThatThrownBy(() -> storageService.upload(OBJECT_KEY, emptyContent, 0, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mockMinioClient, never()).putObject(any(PutObjectArgs.class));
    }

    // ────────────────────────────────────────────────
    // download
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("download → объект есть → возвращается поток из MinIO")
    void download_existingObject_returnsStream() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(mockMinioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse());

        // then
        try (InputStream content = storageService.download(OBJECT_KEY)) {
            verify(mockMinioClient).getObject(getCaptor.capture());
            assertThat(getCaptor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(getCaptor.getValue().object()).isEqualTo(OBJECT_KEY);
            assertThat(content.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("download(смещение, длина) → в MinIO уходит запрос только на фрагмент [offset, offset+length)")
    void download_ranged_passesOffsetAndLength() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(mockMinioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse());

        // then
        try (InputStream content = storageService.download(OBJECT_KEY, 5, 3)) {
            verify(mockMinioClient).getObject(getCaptor.capture());
            assertThat(getCaptor.getValue().offset()).isEqualTo(5L);
            assertThat(getCaptor.getValue().length()).isEqualTo(3L);
            assertThat(content.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("download без диапазона → offset и length не задаются: читается весь объект")
    void download_fullObject_setsNoRangeArgs() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(mockMinioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse());

        // then
        try (InputStream content = storageService.download(OBJECT_KEY)) {
            verify(mockMinioClient).getObject(getCaptor.capture());
            assertThat(getCaptor.getValue().offset()).isNull();
            assertThat(getCaptor.getValue().length()).isNull();
            assertThat(content.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("download с отрицательным смещением → IllegalArgumentException, в MinIO не ходим")
    void download_negativeOffset_throwsIllegalArgumentException() throws Exception {

        // given / when / then
        assertThatThrownBy(() -> storageService.download(OBJECT_KEY, -1, 5))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mockMinioClient, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    @DisplayName("download → объекта нет (NoSuchKey) → StorageException со статусом 404")
    void download_missingObject_throwsNotFound() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(mockMinioClient.getObject(any(GetObjectArgs.class))).thenThrow(errorResponse("NoSuchKey"));

        StorageException exception = catchThrowableOfType(StorageException.class,
                () -> storageService.download(OBJECT_KEY));

        // then
        assertThat(exception.getStatus().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("download → прочая ошибка MinIO → StorageException 503")
    void download_clientFailure_throwsServiceUnavailable() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(mockMinioClient.getObject(any(GetObjectArgs.class))).thenThrow(errorResponse("InternalError"));

        StorageException exception = catchThrowableOfType(StorageException.class,
                () -> storageService.download(OBJECT_KEY));

        // then
        assertThat(exception.getStatus().value()).isEqualTo(503);
    }

    // ────────────────────────────────────────────────
    // delete / ensureBucket
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("delete → ошибка MinIO не всплывает: удаление best-effort для отката загрузки")
    void delete_clientFailure_isSwallowed() throws Exception {

        // given
        // when
        doThrow(new IOException("gone")).when(mockMinioClient).removeObject(any(RemoveObjectArgs.class));

        storageService.delete(OBJECT_KEY);

        // then
        verify(mockMinioClient).removeObject(removeCaptor.capture());
        assertThat(removeCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(removeCaptor.getValue().object()).isEqualTo(OBJECT_KEY);
    }

    @Test
    @DisplayName("delete → null-ключ → тихо выходим, в MinIO не ходим")
    void delete_nullKey_doesNothing() throws Exception {

        // given
        // when
        storageService.delete(null);

        // then
        verify(mockMinioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("ensureBucket → бакет уже готов → вторая проверка не выполняется")
    void ensureBucket_existingBucket_checksOnlyOnce() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        storageService.ensureBucket();
        storageService.ensureBucket();

        // then
        verify(mockMinioClient).bucketExists(bucketCaptor.capture());
        assertThat(bucketCaptor.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    @DisplayName("ensureBucket → нет доступа → StorageException, приложение на месте не валится")
    void ensureBucket_clientFailure_throwsStorageException() throws Exception {

        // given
        // when
        when(mockMinioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IOException("unauthorized"));

        // then
        assertThatThrownBy(() -> storageService.ensureBucket()).isInstanceOf(StorageException.class)
                .hasMessageContaining("не подготовлен");
    }

    private ErrorResponseException errorResponse(String code) {
        return new ErrorResponseException(
                new ErrorResponse(code, "error", BUCKET, OBJECT_KEY, "/" + BUCKET, "req", "host"), null, "");
    }

    private GetObjectResponse getObjectResponse() {
        return new GetObjectResponse(new Headers.Builder().build(), BUCKET, null, OBJECT_KEY,
                new ByteArrayInputStream(CONTENT));
    }
}

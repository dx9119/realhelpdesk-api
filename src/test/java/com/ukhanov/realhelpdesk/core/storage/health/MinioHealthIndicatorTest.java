package com.ukhanov.realhelpdesk.core.storage.health;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.ukhanov.realhelpdesk.core.storage.config.MinioProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("Health-индикатор MinIO (MinioHealthIndicator)")
class MinioHealthIndicatorTest {

    @Mock
    private MinioClient minioClient;

    private MinioProperties properties;
    private MinioHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setBucket("attachments");
        indicator = new MinioHealthIndicator(minioClient, properties);
    }

    @Test
    @DisplayName("Бакет существует → UP с именем бакета")
    void health_bucketExists_returnsUpWithBucket() throws Exception {

        // given
        // when
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "attachments");
    }

    @Test
    @DisplayName("Бакета ещё нет → UP: создаётся при первом обращении, это не сбой")
    void health_bucketMissing_returnsUpNotCreatedYet() throws Exception {

        // given
        // when
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "not created yet");
    }

    @Test
    @DisplayName("MinIO недоступен → DOWN с endpoint в деталях")
    void health_minioUnreachable_returnsDownWithEndpoint() throws Exception {

        // given
        // when
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenAnswer(invocation -> {
            throw new IOException("Connection refused");
        });

        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("endpoint", "http://minio:9000");
    }

    @Test
    @DisplayName("Результат кэшируется: повторный опрос в течение TTL не ходит в MinIO")
    void health_repeatedPollWithinTtl_callsMinioOnce() throws Exception {

        // given
        // when
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        indicator.health();
        indicator.health();

        // then
        verify(minioClient, times(1)).bucketExists(any(BucketExistsArgs.class));
    }
}

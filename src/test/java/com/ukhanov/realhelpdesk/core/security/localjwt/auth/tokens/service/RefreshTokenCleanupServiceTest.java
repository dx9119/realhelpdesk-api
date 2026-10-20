package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.RefreshTokenCleanupService.CleanupResult;
import com.ukhanov.realhelpdesk.core.security.localjwt.config.JwtProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Ротация refresh-токенов: оценка объёма, батчи, адаптивный размер и проверка конфигурации")
class RefreshTokenCleanupServiceTest {

    private final JwtRefreshTokenRepository repository = mock(JwtRefreshTokenRepository.class);
    private final RefreshTokenCleanupExecutor executor = mock(RefreshTokenCleanupExecutor.class);

    @Test
    @DisplayName("Оценка объёма считается до удаления, обе группы удаляются батчами")
    void deleteExpiredTokens_bothGroupsPresent_countsAndPurgesBoth() {

        // given
        // when
        when(repository.countByStatusNotAndCreatedAtBefore(eq(TokenStatus.ACTIVE), any())).thenReturn(5L);
        when(repository.countByStatusAndCreatedAtBefore(eq(TokenStatus.ACTIVE), any())).thenReturn(3L);
        when(executor.deleteInactiveBatch(any(), anyInt())).thenReturn(5);
        when(executor.deleteActiveBatch(any(), anyInt())).thenReturn(3);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor, properties());

        service.deleteExpiredTokens();

        // then
        verify(repository).countByStatusNotAndCreatedAtBefore(eq(TokenStatus.ACTIVE), any());
        verify(repository).countByStatusAndCreatedAtBefore(eq(TokenStatus.ACTIVE), any());
        verify(executor).deleteInactiveBatch(any(), eq(100));
        verify(executor).deleteActiveBatch(any(), eq(100));
    }

    @Test
    @DisplayName("Полный батч продолжает цикл с удвоенным размером, затем останавливается на пустом")
    void deleteExpiredTokens_fullBatch_doublesNextBatchSize() {

        // given
        // when
        when(executor.deleteInactiveBatch(any(), anyInt())).thenReturn(100, 0);
        when(executor.deleteActiveBatch(any(), anyInt())).thenReturn(0);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor, properties());

        service.deleteExpiredTokens();

        ArgumentCaptor<Integer> batches = ArgumentCaptor.forClass(Integer.class);
        // then
        verify(executor, org.mockito.Mockito.times(2)).deleteInactiveBatch(any(), batches.capture());
        assertThat(batches.getAllValues()).as("быстрый полный батч удваивает размер следующего").containsExactly(100, 200);
    }

    @Test
    @DisplayName("Принудительная очистка чистит только отозванные и возвращает счётчики")
    void cleanRevokedTokens_revokedPresent_returnsCountersAndSkipsActive() {

        // given
        // when
        when(repository.countByStatusNotAndCreatedAtBefore(eq(TokenStatus.ACTIVE), any())).thenReturn(5L);
        when(executor.deleteInactiveBatch(any(), anyInt())).thenReturn(2);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor, properties());

        CleanupResult result = service.cleanRevokedTokens();

        // then
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(1);
        verify(executor).deleteInactiveBatch(any(), eq(100));
        verify(executor, org.mockito.Mockito.never()).deleteActiveBatch(any(), anyInt());
        verify(repository, org.mockito.Mockito.never()).countByStatusAndCreatedAtBefore(any(), any());
    }

    @Test
    @DisplayName("Неполный батч завершает группу: строк больше нет")
    void deleteExpiredTokens_partialBatch_stopsAfterSinglePass() {

        // given
        // when
        when(executor.deleteInactiveBatch(any(), anyInt())).thenReturn(50);
        when(executor.deleteActiveBatch(any(), anyInt())).thenReturn(0);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor, properties());

        service.deleteExpiredTokens();

        // 50 < запрошенных 100 — цикл не продолжается, повторной выборки нет
        // then
        verify(executor, org.mockito.Mockito.times(1)).deleteInactiveBatch(any(), eq(100));
    }

    @Test
    @DisplayName("Пустой батч останавливает группу: по одному запросу на каждую")
    void deleteExpiredTokens_emptyBatch_queriesEachGroupOnce() {

        // given
        // when
        when(executor.deleteInactiveBatch(any(), anyInt())).thenReturn(0);
        when(executor.deleteActiveBatch(any(), anyInt())).thenReturn(0);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor, properties());

        service.deleteExpiredTokens();

        // then
        verify(executor, org.mockito.Mockito.times(1)).deleteInactiveBatch(any(), eq(100));
        verify(executor, org.mockito.Mockito.times(1)).deleteActiveBatch(any(), eq(100));
    }

    @Test
    @DisplayName("Прерывание во время паузы останавливает прогон, поток помечается прерванным")
    void deleteExpiredTokens_interruptedDuringPause_stopsRun() {

        // given
        // when
        when(executor.deleteInactiveBatch(any(), anyInt())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return 100;
        });
        when(executor.deleteActiveBatch(any(), anyInt())).thenReturn(0);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(repository, executor,
                properties(604800, 2592000, "0 30 3 * * *", 100, 5000, 200, 50));

        try {
            service.deleteExpiredTokens();
        } finally {
            Thread.interrupted();
        }

        // then
        verify(executor, org.mockito.Mockito.times(1)).deleteInactiveBatch(any(), eq(100));
    }

    @Test
    @DisplayName("Адаптивный размер: растёт на быстром батче, уменьшается на медленном, держит границы")
    void adaptBatchSize_variousDurations_staysWithinBounds() {

        // given / when / then
        assertThat(RefreshTokenCleanupService.adaptBatchSize(100, 10, 200, 100, 5_000)).as("быстро — вдвое").isEqualTo(200);
        assertThat(RefreshTokenCleanupService.adaptBatchSize(4_000, 10, 200, 100, 5_000)).as("рост упирается в максимум").isEqualTo(5_000);
        assertThat(RefreshTokenCleanupService.adaptBatchSize(200, 300, 200, 100, 5_000)).as("медленно — вдвое меньше").isEqualTo(100);
        assertThat(RefreshTokenCleanupService.adaptBatchSize(100, 300, 200, 100, 5_000)).as("ниже минимума не уходит").isEqualTo(100);
        assertThat(RefreshTokenCleanupService.adaptBatchSize(200, 150, 200, 100, 5_000)).as("в пределах цели — без изменений")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Невалидные пороги/расписание/батчи — старт падает с понятной ошибкой")
    void constructor_invalidProperties_throwsIllegalState() {

        // given / when / then
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(0, 2592000, "0 30 3 * * *", 100, 5000, 200, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked-token-retention-seconds");
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(604800, 0, "0 30 3 * * *", 100, 5000, 200, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refresh-token-expiration-seconds");
        assertThatThrownBy(() -> new RefreshTokenCleanupService(repository, executor, properties(604800, 2592000, " ", 100, 5000, 200, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("token-cleanup-cron");
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(604800, 2592000, "0 30 3 * * *", 0, 5000, 200, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("batch-min-size");
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(604800, 2592000, "0 30 3 * * *", 100, 50, 200, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("batch-max-size");
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(604800, 2592000, "0 30 3 * * *", 100, 5000, 0, 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("target-batch-ms");
        assertThatThrownBy(
                () -> new RefreshTokenCleanupService(repository, executor, properties(604800, 2592000, "0 30 3 * * *", 100, 5000, 200, -1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("batch-pause-ms");
    }

    private JwtProperties properties() {
        return properties(604800, 2592000, "0 30 3 * * *", 100, 5000, 200, 0);
    }

    private JwtProperties properties(Integer retentionSeconds, Integer refreshSeconds, String cron, Integer batchMin, Integer batchMax,
            Integer targetMs, Integer pauseMs) {
        JwtProperties properties = new JwtProperties();
        properties.setRevokedTokenRetentionSeconds(retentionSeconds);
        properties.setRefreshTokenExpirationSeconds(refreshSeconds);
        properties.setTokenCleanupCron(cron);
        properties.setTokenCleanupBatchMinSize(batchMin);
        properties.setTokenCleanupBatchMaxSize(batchMax);
        properties.setTokenCleanupTargetBatchMs(targetMs);
        properties.setTokenCleanupBatchPauseMs(pauseMs);
        return properties;
    }
}

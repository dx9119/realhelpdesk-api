package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Исполнитель батча очистки: выборка порции и удаление по id в отдельной транзакции")
class RefreshTokenCleanupExecutorTest {

    private static final Instant CUTOFF = Instant.now().minusSeconds(3600);

    private final JwtRefreshTokenRepository repository = mock(JwtRefreshTokenRepository.class);
    private final RefreshTokenCleanupExecutor executor = new RefreshTokenCleanupExecutor(repository);

    @Test
    @DisplayName("Отозванные: выбирает порцию и удаляет её по id, возвращая число удалённых")
    void deleteInactiveBatch_tokensFound_deletesBatchAndReturnsCount() {

        // given
        // when
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findInactiveIdsBefore(eq(TokenStatus.ACTIVE), eq(CUTOFF), any())).thenReturn(ids);
        when(repository.deleteByUuidIn(ids)).thenReturn(2);

        int deleted = executor.deleteInactiveBatch(CUTOFF, 50);

        // then
        assertThat(deleted).isEqualTo(2);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findInactiveIdsBefore(eq(TokenStatus.ACTIVE), eq(CUTOFF), page.capture());
        assertThat(page.getValue().getPageSize()).as("размер страницы — запрошенный батч").isEqualTo(50);
        assertThat(page.getValue().getPageNumber()).as("берём всегда первые — самые старые").isZero();
        verify(repository).deleteByUuidIn(ids);
    }

    @Test
    @DisplayName("Активные: идёт по своей выборке, пустая порция — 0 без удаления")
    void deleteActiveBatch_emptyBatch_returnsZeroWithoutDelete() {

        // given
        // when
        when(repository.findActiveIdsBefore(eq(TokenStatus.ACTIVE), eq(CUTOFF), any())).thenReturn(List.of());

        int deleted = executor.deleteActiveBatch(CUTOFF, 100);

        // then
        assertThat(deleted).isZero();
        verify(repository).findActiveIdsBefore(eq(TokenStatus.ACTIVE), eq(CUTOFF), any());
        verify(repository, org.mockito.Mockito.never()).deleteByUuidIn(any());
    }

    @Test
    @DisplayName("Отозванные пусты — удаление не вызывается")
    void deleteInactiveBatch_emptyBatch_returnsZeroWithoutDelete() {

        // given
        // when
        when(repository.findInactiveIdsBefore(any(), any(), any())).thenReturn(List.of());

        // then
        assertThat(executor.deleteInactiveBatch(CUTOFF, 100)).isZero();

        verify(repository, org.mockito.Mockito.never()).deleteByUuidIn(any());
    }
}

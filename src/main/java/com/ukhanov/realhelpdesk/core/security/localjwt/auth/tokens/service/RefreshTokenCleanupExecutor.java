package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository.JwtRefreshTokenRepository;

import lombok.RequiredArgsConstructor;

/**
 * Исполнитель одного батча очистки {@code jwt_tokens}: выборка порции идентификаторов и их удаление идут в отдельной
 * короткой транзакции ({@code REQUIRES_NEW}). Оркестратор {@link RefreshTokenCleanupService} вызывает батчи по одному и
 * делает паузы между ними, поэтому очистка не превращается в одну длинную транзакцию с всплеском I/O и блокировок.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupExecutor {

    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;

    /** Удаляет одну порцию отозванных строк старше порога; возвращает число удалённых (0 — строк больше нет). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteInactiveBatch(Instant cutoff, int batchSize) {
        return deleteBatch(true, cutoff, batchSize);
    }

    /** Удаляет одну порцию активных строк старше срока жизни; возвращает число удалённых (0 — строк больше нет). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteActiveBatch(Instant cutoff, int batchSize) {
        return deleteBatch(false, cutoff, batchSize);
    }

    private int deleteBatch(boolean inactive, Instant cutoff, int batchSize) {
        List<UUID> ids = inactive
                ? jwtRefreshTokenRepository.findInactiveIdsBefore(TokenStatus.ACTIVE, cutoff,
                        PageRequest.of(0, batchSize))
                : jwtRefreshTokenRepository.findActiveIdsBefore(TokenStatus.ACTIVE, cutoff,
                        PageRequest.of(0, batchSize));
        if (ids.isEmpty()) {
            return 0;
        }
        return jwtRefreshTokenRepository.deleteByUuidIn(ids);
    }
}

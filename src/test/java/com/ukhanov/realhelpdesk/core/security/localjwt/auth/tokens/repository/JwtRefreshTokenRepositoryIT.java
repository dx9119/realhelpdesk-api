package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.ukhanov.realhelpdesk.PostgresIntegrationTest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Батч-очистка jwt_tokens (JwtRefreshTokenRepository)")
class JwtRefreshTokenRepositoryIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF = NOW.minusSeconds(3600);
    private static final Instant OLD = NOW.minusSeconds(7200);
    private static final Instant FRESH = NOW.minusSeconds(60);

    @Autowired
    private JwtRefreshTokenRepository repository;

    @Autowired
    private EntityManager em;

    private UserModel user;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        user = new UserModel();
        user.setFirstName("Тест");
        user.setLastName("Тестов");
        user.setEmail("cleanup-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash");
        em.persist(user);
        em.flush();
    }

    @Test
    @DisplayName("Оценка объёма и выборка порции: только строки старше порога, нужной группы и не больше батча")
    void estimateAndSelectBatch() {
        UUID revokedOld = save(TokenStatus.REVOKED, OLD);
        UUID activeOld = save(TokenStatus.ACTIVE, OLD);
        UUID revokedFresh = save(TokenStatus.REVOKED, FRESH);
        save(TokenStatus.ACTIVE, FRESH);

        // оценка объёма считает только строки старше порога
        assertThat(repository.countByStatusNotAndCreatedAtBefore(TokenStatus.ACTIVE, CUTOFF)).isEqualTo(1);
        assertThat(repository.countByStatusAndCreatedAtBefore(TokenStatus.ACTIVE, CUTOFF)).isEqualTo(1);

        // выборка порции делит отозванные и активные, свежие строки не попадают
        assertThat(repository.findInactiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 10))).containsExactly(revokedOld);
        assertThat(repository.findActiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 10))).containsExactly(activeOld);

        // размер батча ограничивает выборку
        assertThat(repository.findInactiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 1))).hasSize(1);
        assertThat(repository.findInactiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 10))).doesNotContain(revokedFresh);
    }

    @Test
    @DisplayName("Сортировка старыми первыми: батч всегда забирает самые старые строки")
    void oldestFirst() {
        UUID oldest = save(TokenStatus.REVOKED, NOW.minusSeconds(9000));
        UUID middle = save(TokenStatus.REVOKED, NOW.minusSeconds(8000));
        save(TokenStatus.REVOKED, FRESH);

        assertThat(repository.findInactiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 1))).containsExactly(oldest);
        assertThat(repository.findInactiveIdsBefore(TokenStatus.ACTIVE, CUTOFF, PageRequest.of(0, 2))).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("Пакетное удаление: удаляет только переданные id, остальные строки не трогает")
    void deleteBatchByIds() {
        UUID first = save(TokenStatus.REVOKED, OLD);
        UUID second = save(TokenStatus.REVOKED, OLD);
        UUID kept = save(TokenStatus.ACTIVE, OLD);

        int deleted = repository.deleteByUuidIn(List.of(first, second));

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findById(first)).isEmpty();
        assertThat(repository.findById(second)).isEmpty();
        assertThat(repository.findById(kept)).isPresent();
    }

    private UUID save(TokenStatus status, Instant createdAt) {
        RefreshTokenModel token = new RefreshTokenModel();
        token.setTokenRefresh((UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""));
        token.setStatus(status);
        token.setUser(user);
        repository.saveAndFlush(token);
        // createdAt выставляет только @PrePersist и сеттера снаружи нет — сдвигаем назад bulk-запросом
        em.createQuery("UPDATE RefreshTokenModel t SET t.createdAt = :ts WHERE t.uuid = :id").setParameter("ts", createdAt)
                .setParameter("id", token.getUuid()).executeUpdate();
        em.flush();
        em.clear();
        return token.getUuid();
    }
}

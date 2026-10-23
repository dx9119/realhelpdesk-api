package com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;

@Repository
public interface JwtRefreshTokenRepository extends JpaRepository<RefreshTokenModel, UUID> {
    List<RefreshTokenModel> findAllByUserEmailAndStatus(String email, TokenStatus status);

    /**
     * Bulk-отзыв одним UPDATE: вместо N селектов и N сохранений сущностей при ротации или смене пароля.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
                UPDATE RefreshTokenModel t
                SET t.status = :nextStatus
                WHERE t.user.email = :email
                  AND t.status = :currentStatus
            """)
    int updateStatusByEmailAndStatus(@Param("email") String email, @Param("currentStatus") TokenStatus currentStatus,
            @Param("nextStatus") TokenStatus nextStatus);

    // требуется забирать UserModel по refresh-токену
    @EntityGraph(attributePaths = "user")
    Optional<RefreshTokenModel> findByTokenRefresh(String tokenRefresh);

    /** Оценка объёма: сколько отозванных строк старше порога. Считается по индексу {@code created_at} до удаления. */
    long countByStatusNotAndCreatedAtBefore(TokenStatus status, Instant cutoff);

    /** Оценка объёма: сколько активных строк старше срока жизни refresh-токена. */
    long countByStatusAndCreatedAtBefore(TokenStatus status, Instant cutoff);

    /**
     * Идентификаторы отозванных строк старше порога, по одному батчу: {@link Pageable} ограничивает выборку, сортировка
     * — старые первыми.
     */
    @Query("SELECT t.uuid FROM RefreshTokenModel t WHERE t.status <> :activeStatus AND t.createdAt < :cutoff "
            + "ORDER BY t.createdAt ASC")
    List<UUID> findInactiveIdsBefore(@Param("activeStatus") TokenStatus activeStatus, @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /** Идентификаторы активных строк старше срока жизни refresh-токена, по одному батчу. */
    @Query("SELECT t.uuid FROM RefreshTokenModel t WHERE t.status = :activeStatus AND t.createdAt < :cutoff "
            + "ORDER BY t.createdAt ASC")
    List<UUID> findActiveIdsBefore(@Param("activeStatus") TokenStatus activeStatus, @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /**
     * Удаление одного батча по идентификаторам: вместо монолитного {@code DELETE} таблицы — короткая транзакция на
     * порцию, чтобы очистка не создавала пиковой нагрузки на БД.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RefreshTokenModel t WHERE t.uuid IN :ids")
    int deleteByUuidIn(@Param("ids") Collection<UUID> ids);

}

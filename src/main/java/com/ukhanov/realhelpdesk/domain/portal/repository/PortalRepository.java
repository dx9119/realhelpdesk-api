package com.ukhanov.realhelpdesk.domain.portal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

@Repository
public interface PortalRepository extends JpaRepository<PortalModel, Long> {

    /**
     * Портал вместе с составом допущенных пользователей: состав читается почти в каждой операции (проверка прав,
     * рассылка, оповещения), а после закрытия транзакции сущность становится detached — LAZY-коллекция там уже не
     * догрузится. Для одного портала это один JOIN, а не N+1.
     */
    @EntityGraph(attributePaths = "allowedUserIds")
    Optional<PortalModel> findWithAccessById(Long id);

    @Query("SELECT p FROM PortalModel p WHERE p.owner.id = :ownerId AND p.isDeleted = false ORDER BY p.createdAt DESC")
    List<PortalModel> findAllByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") Long ownerId);

    @Query("SELECT p FROM PortalModel p WHERE p.owner.id = :ownerId AND p.isDeleted = false ORDER BY p.createdAt DESC")
    Page<PortalModel> findAllByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") Long ownerId, Pageable pageable);

    @Query("SELECT p FROM PortalModel p WHERE :userId MEMBER OF p.allowedUserIds AND p.isDeleted = false")
    Page<PortalModel> findAccessibleByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT p FROM PortalModel p WHERE :userId MEMBER OF p.allowedUserIds AND p.isDeleted = false")
    List<PortalModel> findAllAccessibleByUserId(@Param("userId") Long userId);

    boolean existsByOwnerIdAndNameAndIsDeletedFalse(Long ownerId, String name);

    @Query("SELECT COUNT(p) FROM PortalModel p WHERE p.owner.id = :ownerId AND p.isDeleted = false")
    Integer countPortalByOwnerId(@Param("ownerId") Long ownerId);

    @Query("SELECT SIZE(p.allowedUserIds) FROM PortalModel p WHERE p.id = :portalId AND p.isDeleted = false")
    Integer countAllowedUsersByPortalId(@Param("portalId") Long portalId);

    @Query("""
                SELECT DISTINCT p.id
                FROM PortalModel p
                JOIN TicketModel t ON t.portal.id = p.id
                WHERE p.isPublic = true
                  AND p.isDeleted = false
                  AND t.author.id = :userId
            """)
    List<Long> findPublicPortalIdsWithUserTickets(@Param("userId") Long userId);

}

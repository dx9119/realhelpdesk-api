package com.ukhanov.realhelpdesk.domain.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.transaction.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationModel, Long> {

    Page<NotificationModel> findByRecipientId(Long recipientId, Pageable pageable);

    Page<NotificationModel> findByRecipientIdAndReadFalse(Long recipientId, Pageable pageable);

    /** Новые для long polling: строго больше курсора, по возрастанию id — клиент догоняет пачками. */
    List<NotificationModel> findByRecipientIdAndIdGreaterThanOrderByIdAsc(Long recipientId, Long afterId,
            Pageable pageable);

    Optional<NotificationModel> findByIdAndRecipientId(Long id, Long recipientId);

    long countByRecipientIdAndReadFalse(Long recipientId);

    /**
     * Кандидаты на повтор: непрочитанные строки повторяемых событий, являющиеся последними в своей группе (более новые
     * непрочитанные копии той же группы отсекаются, чтобы цепочка повторов не размножалась). Следующий фильтр — по
     * настройкам получателя — в коде.
     */
    @Query("""
            select n from NotificationModel n
            where n.read = false
              and n.event in :events
              and not exists (
                  select 1 from NotificationModel m
                  where m.read = false
                    and m.recipientId = n.recipientId
                    and m.event = n.event
                    and coalesce(m.groupId, m.id) = coalesce(n.groupId, n.id)
                    and m.id > n.id)
            """)
    List<NotificationModel> findRepeatCandidates(@Param("events") Set<NotificationEvent> events, Pageable pageable);

    @Modifying
    @Transactional
    @Query("update NotificationModel n set n.read = true where n.recipientId = :recipientId and n.read = false")
    int markAllReadByRecipientId(@Param("recipientId") Long recipientId);

    /** Прочтение одной строки гасит всю её группу повторов (оригинал и все напоминания). */
    @Modifying
    @Transactional
    @Query("""
            update NotificationModel n set n.read = true
            where n.recipientId = :recipientId and n.read = false and (n.id = :groupId or n.groupId = :groupId)
            """)
    int markGroupReadByRecipientId(@Param("recipientId") Long recipientId, @Param("groupId") Long groupId);
}

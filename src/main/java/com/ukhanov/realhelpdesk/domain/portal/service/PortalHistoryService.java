package com.ukhanov.realhelpdesk.domain.portal.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryModel;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalHistoryRecordCommand;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Запись истории портала: передачи владения и изменения самого портала. Вызывается в той же транзакции, что и само
 * изменение, поэтому история не расходится с состоянием портала; обрезка длинных полей — по ограничениям колонок.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class PortalHistoryService {

    private static final int REASON_MAX_LENGTH = 2000;
    private static final int FIELD_NAME_MAX_LENGTH = 40;
    private static final int VALUE_MAX_LENGTH = 1000;

    private final PortalHistoryRepository portalHistoryRepository;
    /**
     * Системное событие (actorId = null) или действие пользователя: actorId — кто выполнил, targetUserId — второй
     * участник.
     */
    public void record(PortalHistoryRecordCommand command) {
        PortalHistoryModel entry = new PortalHistoryModel();
        entry.setPortalId(command.portalId());
        entry.setEvent(command.event());
        entry.setActorId(command.actorId());
        entry.setTargetUserId(command.targetUserId());
        entry.setReason(truncate(command.reason(), REASON_MAX_LENGTH));
        entry.setFieldName(truncate(command.fieldName(), FIELD_NAME_MAX_LENGTH));
        entry.setOldValue(truncate(command.oldValue(), VALUE_MAX_LENGTH));
        entry.setNewValue(truncate(command.newValue(), VALUE_MAX_LENGTH));

        portalHistoryRepository.save(entry);
        logger.debug("История портала {}: {} (актор {})", command.portalId(), command.event(), command.actorId());
    }

    /** Записи истории портала; порядок задаёт Pageable (по умолчанию клиент просит createdAt desc). */
    public Page<PortalHistoryModel> getPage(Long portalId, Pageable pageable) {
        return portalHistoryRepository.findByPortalId(portalId, pageable);
    }

    /** Значение поля портала для истории: null не должен ронять запись. */
    public static String valueOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

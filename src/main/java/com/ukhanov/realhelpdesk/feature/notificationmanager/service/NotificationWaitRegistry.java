package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.exception.SafeSideEffectExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * Реестр ожидающих long-polling запросов оповещений: один инстанс приложения, поэтому достаточно общих структур в
 * памяти. Клиент регистрирует колбэк ({@code onWake}), публикатор будит его после коммита новой записи — ожидающий
 * запрос сразу перечитывает БД и отвечает, не дожидаясь таймаута.
 */
@Slf4j
@Component
public class NotificationWaitRegistry {

    private final ConcurrentMap<Long, List<Runnable>> waiters = new ConcurrentHashMap<>();

    public void register(Long userId, Runnable onWake) {
        if (userId == null || onWake == null) {
            return;
        }
        waiters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(onWake);
    }

    public void unregister(Long userId, Runnable onWake) {
        if (userId == null || onWake == null) {
            return;
        }
        List<Runnable> userWaiters = waiters.get(userId);
        if (userWaiters == null) {
            return;
        }
        userWaiters.remove(onWake);
        if (userWaiters.isEmpty()) {
            waiters.remove(userId, userWaiters);
        }
    }

    /** Будит всех ожидающих пользователя; реестр очищается — каждый колбэк срабатывает один раз. */
    public void wake(Long userId) {
        if (userId == null) {
            return;
        }
        List<Runnable> userWaiters = waiters.remove(userId);
        if (userWaiters == null) {
            return;
        }
        for (Runnable onWake : userWaiters) {
            // Снимок контекста будящего потока: колбэк чистит MDC в finally, поэтому после его сбоя
            // строка WARN ушла бы с пустыми колонками. Восстанавливаем контекст инициатора пробуждения
            // (поток запроса-публикации или технический id планировщика)
            Map<String, String> wakeContext = MDC.getCopyOfContextMap();
            SafeSideEffectExecutor.runSafely(() -> {
                onWake.run();
                return null;
            }, e -> {
                if (wakeContext != null) {
                    MDC.setContextMap(wakeContext);
                }
                logger.warn("Ошибка пробуждения ожидающего запроса оповещений пользователя {}", userId, e);
            });
        }
    }

    public int waiterCount(Long userId) {
        List<Runnable> userWaiters = waiters.get(userId);
        return userWaiters == null ? 0 : userWaiters.size();
    }
}

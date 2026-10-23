package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ukhanov.realhelpdesk.core.pagination.dto.PageResponse;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.domain.notification.model.NotificationModel;
import com.ukhanov.realhelpdesk.domain.notification.repository.NotificationRepository;
import com.ukhanov.realhelpdesk.feature.notificationmanager.dto.NotificationResponse;
import com.ukhanov.realhelpdesk.feature.notificationmanager.mapper.NotificationMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE-канал in-app оповещений: держит курсор подписки, отдаёт новые события и после каждого пробуждения снова
 * регистрируется в {@link NotificationWaitRegistry}. Вынесен из {@code NotificationManageService}, чтобы чтение
 * списка/настроек и потоковая отдача не разрастались в один класс.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSseService {

    private static final Duration SSE_TIMEOUT = Duration.ofMinutes(30);

    private final NotificationRepository notificationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationWaitRegistry waitRegistry;
    private final NotificationMapper notificationMapper;

    public SseEmitter subscribe(long afterId, int size) {
        Long userId = currentUserProvider.getCurrentUserId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        NotificationStream stream = new NotificationStream(emitter, userId, afterId, size, MDC.getCopyOfContextMap());

        // Первый пакет открывает поток: клиент сразу видит соединение, а прокси — что поток живой.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            logger.debug("SSE-канал оповещений пользователя {} не открылся: {}", userId, e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }

        waitRegistry.register(userId, stream);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.close());

        stream.push();
        logger.debug("SSE-канал оповещений открыт: пользователь {}, afterId {}", userId, afterId);
        return emitter;
    }

    private PageResponse<NotificationResponse> fetchAfter(Long userId, long afterId, int size) {
        List<NotificationModel> rows = notificationRepository.findByRecipientIdAndIdGreaterThanOrderByIdAsc(userId,
                afterId,
                PageRequest.of(0, size));
        List<NotificationResponse> content = rows.stream().map(notificationMapper::toResponse).toList();

        return new PageResponse.Builder<NotificationResponse>().content(content)
                .page(0)
                .size(size)
                .totalElements(content.size())
                .totalPages(content.isEmpty() ? 0 : 1)
                .last(true)
                .build();
    }

    private final class NotificationStream implements Runnable {

        private final SseEmitter emitter;
        private final Long userId;
        private final AtomicLong cursor;
        private final int size;
        private final Map<String, String> mdcSnapshot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private NotificationStream(SseEmitter emitter, Long userId, long afterId, int size,
                Map<String, String> mdcSnapshot) {
            this.emitter = emitter;
            this.userId = userId;
            this.cursor = new AtomicLong(afterId);
            this.size = size;
            this.mdcSnapshot = mdcSnapshot;
        }

        @Override
        public void run() {
            // Колбэк приходит с потока публикатора/планировщика: подставляем контекст запроса,
            // который открыл канал, а в finally возвращаем прежний контекст будящего потока.
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                push();
                if (!closed.get()) {
                    waitRegistry.register(userId, this);
                }
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        }

        /** Отдаёт все оповещения новее курсора; разрыв соединения закрывает подписку. */
        void push() {
            if (closed.get()) {
                return;
            }
            try {
                for (NotificationResponse notification : fetchAfter(userId, cursor.get(), size).content()) {
                    emitter.send(SseEmitter.event().name("notification").data(notification));
                    cursor.set(Math.max(cursor.get(), notification.id()));
                }
            } catch (IOException | IllegalStateException e) {
                logger.info("SSE-канал оповещений закрыт: userId={}, причина={}", userId, e.getMessage());
                close();
                emitter.completeWithError(e);
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                waitRegistry.unregister(userId, this);
            }
        }
    }
}

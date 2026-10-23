package com.ukhanov.realhelpdesk.core.security.ratelimit.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RateLimitService {

    private static final int SWEEP_THRESHOLD = 1000;
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long expiresAt, AtomicInteger count) {
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    public Decision check(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        sweepExpired(now);

        Window current = windows.compute(key,
                (k, existing) -> existing == null || existing.expiresAt() <= now
                        ? new Window(now + windowMillis, new AtomicInteger(0))
                        : existing);

        int count = current.count().incrementAndGet();
        boolean allowed = count <= maxRequests;
        long retryAfterSeconds = 0;
        if (!allowed) {
            long retryAfterMillis = Math.max(0, current.expiresAt() - now);
            retryAfterSeconds = Math.max(1, (retryAfterMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
        }

        logger.debug("Лимит {}: запросов={}, макс={}, allowed={}, retryAfter={}с", key, count, maxRequests, allowed,
                retryAfterSeconds);
        return new Decision(allowed, retryAfterSeconds);
    }

    public void reset() {
        windows.clear();
    }

    private void sweepExpired(long now) {
        int size = windows.size();
        if (size <= SWEEP_THRESHOLD) {
            return;
        }
        logger.warn("Окон рейт-лимита больше порога: {}", size);
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }
}

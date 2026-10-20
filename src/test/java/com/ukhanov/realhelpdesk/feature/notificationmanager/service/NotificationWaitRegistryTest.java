package com.ukhanov.realhelpdesk.feature.notificationmanager.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Реестр ожидающих long-polling запросов (NotificationWaitRegistry)")
class NotificationWaitRegistryTest {

    private static final Long USER_ID = 42L;

    private NotificationWaitRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NotificationWaitRegistry();
    }

    @Test
    @DisplayName("wake будит всех зарегистрированных ожидателей пользователя")
    void wake_twoWaitersRegistered_invokesBothAndClears() {

        // given
        // when
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        registry.register(USER_ID, first::incrementAndGet);
        registry.register(USER_ID, second::incrementAndGet);

        registry.wake(USER_ID);

        // then
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
        assertThat(registry.waiterCount(USER_ID)).isZero();
    }

    @Test
    @DisplayName("wake без ожидающих — no-op; другой пользователь не будится")
    void wake_withoutWaiters_isNoOp() {

        // given
        // when
        AtomicInteger other = new AtomicInteger();
        registry.register(7L, other::incrementAndGet);

        registry.wake(USER_ID);

        // then
        assertThat(other.get()).isZero();
        assertThat(registry.waiterCount(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("unregister убирает колбэк: после wake он не срабатывает")
    void unregister_beforeWake_callbackNotInvoked() {

        // given
        // when
        AtomicInteger calls = new AtomicInteger();
        Runnable onWake = calls::incrementAndGet;
        registry.register(USER_ID, onWake);
        registry.unregister(USER_ID, onWake);

        registry.wake(USER_ID);

        // then
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("Исключение в одном ожидателе не мешает остальным")
    void wake_waiterThrows_doesNotAffectOthers() {

        // given
        // when
        AtomicInteger survivor = new AtomicInteger();
        registry.register(USER_ID, () -> {
            throw new IllegalStateException("клиент отключился");
        });
        registry.register(USER_ID, survivor::incrementAndGet);

        // then
        assertThatCode(() -> registry.wake(USER_ID)).doesNotThrowAnyException();
        assertThat(survivor.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("null-пользователь и null-колбэк игнорируются")
    void registry_nullArguments_areIgnored() {

        // given / when / then
        assertThatCode(() -> {
            registry.register(null, () -> {
            });
            registry.register(USER_ID, null);
            registry.unregister(null, () -> {
            });
            registry.wake(null);
        }).doesNotThrowAnyException();
    }
}

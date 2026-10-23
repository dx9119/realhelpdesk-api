package com.ukhanov.realhelpdesk.core.exception;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Граница best-effort side-effect'ов: выполняет действие и передаёт любое исключение обработчику, не пробрасывая его в
 * основной сценарий (SMTP, SSE, in-app оповещения). Checkstyle-подавление {@code IllegalCatch} локализовано здесь.
 */
public final class SafeSideEffectExecutor {

    private SafeSideEffectExecutor() {
    }

    public static boolean runSafely(Callable<Void> action, Consumer<Exception> onError) {
        try {
            action.call();
            return true;
        } catch (Exception e) {
            onError.accept(e);
            return false;
        }
    }

    public static <T> T callSafely(Callable<T> action, Function<Exception, T> onError) {
        try {
            return action.call();
        } catch (Exception e) {
            return onError.apply(e);
        }
    }
}

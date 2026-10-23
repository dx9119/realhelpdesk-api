package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;

/**
 * Тестовый захват payload SSE-emitter: публичной подписки на отправку нет —
 * {@code ResponseBodyEmitter.initialize(Handler)} и интерфейс {@code Handler} package-private, поэтому хелпер лежит в
 * пакете Spring. Подключается сразу после {@code subscribe()}: события, отправленные без обработчика, лежат во
 * внутреннем буфере и всплывают при подключении, дальнейшие приходят напрямую.
 */
public final class SseEmitterCapture {

    private SseEmitterCapture() {
    }

    /** Подключает захват к emitter и возвращает объект с payload'ами и триггерами коллбэков жизненного цикла. */
    public static Capture attach(SseEmitter emitter) {
        Capture capture = new Capture();
        try {
            emitter.initialize(capture);
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось подключить захват SSE-emitter", ex);
        }
        return capture;
    }

    /** Захваченные payload'ы в порядке отправки: строки SSE-формата и объекты данных до сериализации. */
    public static final class Capture implements ResponseBodyEmitter.Handler {

        private final List<Object> payloads = Collections.synchronizedList(new ArrayList<>());
        private volatile Runnable timeoutCallback;
        private volatile Consumer<Throwable> errorCallback;
        private volatile Runnable completionCallback;

        private Capture() {
        }

        public List<Object> payloads() {
            return payloads;
        }

        /** Имитация таймаута асинхронного запроса (контейнер вызывает его у обработчика). */
        public void fireTimeout() {
            Runnable callback = this.timeoutCallback;
            if (callback != null) {
                callback.run();
            }
        }

        /** Имитация ошибки соединения: onError, затем onCompletion — как это делает контейнер. */
        public void fireError(Throwable failure) {
            Consumer<Throwable> onError = this.errorCallback;
            if (onError != null) {
                onError.accept(failure);
            }
            fireCompletion();
        }

        /** Имитация завершения запроса без ошибки. */
        public void fireCompletion() {
            Runnable callback = this.completionCallback;
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void send(Object data, MediaType mediaType) {
            this.payloads.add(data);
        }

        @Override
        public void send(Set<DataWithMediaType> items) {
            items.forEach(item -> this.payloads.add(item.getData()));
        }

        @Override
        public void complete() {
            fireCompletion();
        }

        @Override
        public void completeWithError(Throwable failure) {
            fireError(failure);
        }

        @Override
        public void onTimeout(Runnable callback) {
            this.timeoutCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completionCallback = callback;
        }
    }
}

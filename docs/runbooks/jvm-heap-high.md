# Runbook: JvmHeapHigh

**Алерт**: `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 90%`
в течение 10 минут (`severity: warning`).

## Что это значит

Heap почти заполнен. Дальше — частые Full GC (латентность), затем OOM и рестарт
(см. app-unreachable).

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=jvm_memory_used_bytes{area="heap"}'`
   — «JVM heap» (used vs max), `rate(jvm_gc_pause_seconds_sum[1m])` —
   растёт ли frequency/длительность GC-пауз.
2. **Логи**: `OutOfMemoryError`, «GC overhead limit exceeded», частые Full GC.
3. **Локально**: `jcmd <pid> GC.heap_info` / `jmap -histo` в контейнере
   (`docker exec <app> jcmd 1 GC.heap_info`) — топ-классы по retention.
4. **Кандидаты утечек**: кэш без eviction (guava/caffeine без max size), коллекции
   в статике, непрерывный рост `logback`/attachment-буферов.
5. **Корреляция**: рост started-потоков (`jvm_threads_started_threads_total`) —
   утечка потоков тянет heap через стеки.

## Митигация

- Краткосрочно: увеличить `-Xmx` (учесть лимит контейнера).
- Найти retention-холдер (heap dump в тестовом стенде, не в проде без согласования).
- Проверить кэши: max-size + expire-after-write; гистограммы Micrometer уже
  ограничены шагом 10 — шумных метрик в heap не добавляем.

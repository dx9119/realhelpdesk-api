# Runbook: DatabasePoolSaturation

**Алерт**: `jdbc_connections_active / jdbc_connections_max > 90%` в течение 2 минут
(`severity: warning`).

> **Примечание Boot 4**: `hikaricp_connections_pending > 0` и
> `hikaricp_connections_timeout_total`. Micrometer в Spring Boot 4.1 экспортирует пул
> как `jdbc_connections_{active,idle,max,min}` — метрик pending/timeout в скрейпе нет.
> Насыщение 90% — ранний предвестник; при фактическом исчерпании активные соединения
> упрутся в `jdbc_connections_max` и запросы начнут ждать (таймаут Hikari,
> `hikaricp.connections.timeout` — в логах приложения).

## Что это значит

Пул соединений почти исчерпан. Через минуты возможны таймауты Hikari и волна 5xx
(см. api-high-error-rate).

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=jdbc_connections_active'`
   — active/idle/max, `hibernate_query_executions_total` (queries/s).
2. **Медленные запросы**: приложение логирует slow query (порог `hibernate.log_slow_query`,
   1000 мс): `{container_label_com_docker_compose_service="app"} |= "slow query"`.
3. **Трейсы** (`curl -s -G localhost:3200/api/search --data-urlencode 'q={ duration > 500ms }' --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)" --data-urlencode 'limit=20'`):
   медленные sql-спаны покажут запрос и caller (контроллер/фоновая задача).
4. **Утечки**: active держится у максимума даже при простое → соединения не
   возвращаются (ручные connection в try-with-resources мимо пула, длинные транзакции).
5. **Конкурентность фоновщика**: `executor_active_threads{name="taskScheduler"}` —
   повторы оповещений держат транзакции.

## Митигация

- Увеличить `spring.datasource.hikari.maximum-pool-size` (с запасом до
  `max_connections` postgres × число реплик).
- Убрать долгие транзакции в фон (вынести I/O за пределы транзакции).
- Ограничить периодичность/параллелизм `@Scheduled`-повторов оповещений.
- При росте трафика — вертикальное масштабирование postgres или read-replica.

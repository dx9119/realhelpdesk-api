# Runbook: ApiHighLatencyP99

**Алерт**: p99 времени ответа API > 500 мс (SLO, см. `docs/observability/slo.md`) в течение
10 минут (`severity: warning`).
Метрика: `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))`.

## Что это значит

Медленные ответы для 1% запросов. Пользователи (и агенты) ощущают подвисания; при росте
до секунд падает конверсия и растёт таймаут-нагрузка на шлюзы.

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=histogram_quantile(0.95,rate(http_server_requests_seconds_bucket[5m]))'`
   — какой uri деградировал.
2. **БД**: `jdbc_connections_active` у потолка максимума
   (ждут соединения → латентность растёт); `hibernate_query_executions_total` —
   взрыв запросов (N+1 после правки в репозитории).
3. **JVM**: heap/GC — если GC-паузы подскочили, латентность пойдёт по всем uri.
4. **Трейсы** (`curl -s -G localhost:3200/api/search --data-urlencode 'q={ duration > 500ms }' --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)" --data-urlencode 'limit=20'` —
   окно `start`/`end` обязательно): медленные trace'ы — смотрите, какой спан доминирует
   (sql, external HTTP, scheduler lock); в Grafana трейс сортируется по длительности спанов.
5. **Фоновые задачи**: `tasks_scheduled_execution_seconds` — @Scheduled-повторы
   оповещений могут забирать пул taskScheduler и блокировать HTTP-потоки через БД.

## Митигация

- Найти тяжёлый запрос по трейсам → индекс/кэш/пагинация.
- Пул БД: поднять `maximum-pool-size` (осознанно, с учётом postgres max_connections)
  или уменьшить конкурентность фоновщика.
- Если GC: увеличить heap или урезать аллокации в горячем пути (см. jvm-gc-overhead).

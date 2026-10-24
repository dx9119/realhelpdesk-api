# Runbook: ApiHighErrorRate

**Алерт**: доля HTTP-ответов 5xx > 2% в течение 5 минут (`severity: critical`).
Метрика: `http_server_requests_seconds_count{status=~"5.."}`.

## Что это значит

Более 2% запросов к API завершаются серверной ошибкой (500/502/503/504). Для helpdesk-системы
это означает, что заявки не создаются/не обновляются, агенты не могут работать.

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=rate(http_server_requests_seconds_count{status=~"5.."}[5m])'`
   — локализуйте uri с ошибками.
2. **Логи** (`make dev-logs` или
   `curl -s -G localhost:3100/loki/api/v1/query_range --data-urlencode 'query={container_label_com_docker_compose_service="app"} |= "ERROR"' --data-urlencode 'limit=20'`):
   ищите `exception` (NPE, constraint violation, таймаут БД/MinIO/SMTP).
3. **Трейсы** (`curl -s -G localhost:3200/api/search --data-urlencode 'q={ status = error }' --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)" --data-urlencode 'limit=20'` —
   окно `start`/`end` обязательно, без него Tempo ищет только в живом ingester):
   спаны с `status = error` — видно, на каком слое (repository/service/http) падает запрос.
4. **Зависимости**: `jdbc_connections_active` (БД), health-группа `/actuator/health/smtp`
   (почта), `/actuator/health/minio` (хранилище). Логи Alloy: таймауты connect.
5. **Корреляция**: если ошибки совпали с деплоем/перезапуском — почти наверняка регрессия.

## Митигация

- Откат последнего релиза (если корреляция по времени).
- Если БД: проверить deadlock-и в логах postgres, размер пула (`jdbc_connections_max`).
- Если SMTP/MinIO: health-группа уйдёт в DOWN — заявки при этом продолжают создаваться
  (отправка асинхронная), временно допустимо.
- Если перегрузка: масштабировать реплики app (stateless), кэш тяжёлых чтений.

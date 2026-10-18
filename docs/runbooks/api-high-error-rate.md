# Runbook: ApiHighErrorRate

**Алерт**: доля HTTP-ответов 5xx > 2% в течение 5 минут (`severity: critical`).
Метрика: `http_server_requests_seconds_count{status=~"5.."}`.

## Что это значит

Более 2% запросов к API завершаются серверной ошибкой (500/502/503/504). Для helpdesk-системы
это означает, что заявки не создаются/не обновляются, агенты не могут работать.

## Диагностика

1. **Метрики Mimir**: `curl -s localhost:9090/prometheus/api/v1/query?query=rate(http_server_requests_seconds_count{status=~"5.."}[5m])`
   — локализуйте uri с ошибками.
2. **Логи** (`make logs` или `curl -s "localhost:3100/loki/api/v1/query?query={container_label_com_docker_compose_service=\"app\"} |= \"ERROR\""`):
   ищите `exception` (NPE, constraint violation, таймаут БД/MinIO/SMTP).
3. **Трейсы** (`curl -s localhost:3200/traceql/search`): спаны с `status:error` —
   видно, на каком слое (repository/service/http) падает запрос.
4. **Зависимости**: `jdbc_connections_active` (БД), health-группа `/actuator/health/smtp`
   (почта), `/actuator/health/minio` (хранилище). Логи Alloy: таймауты connect.
5. **Корреляция**: если ошибки совпали с деплоем/перезапуском — почти наверняка регрессия.

## Митигация

- Откат последнего релиза (если корреляция по времени).
- Если БД: проверить deadlock-и в логах postgres, размер пула (`jdbc_connections_max`).
- Если SMTP/MinIO: health-группа уйдёт в DOWN — заявки при этом продолжают создаваться
  (отправка асинхронная), временно допустимо.
- Если перегрузка: масштабировать реплики app (stateless), кэш тяжёлых чтений.

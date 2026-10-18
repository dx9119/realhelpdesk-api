# Инфраструктура наблюдаемости

Стек для локальной разработки: метрики, трейсы и логи. UI не поднимается —
все три сигнала доступны HTTP-API бэкендов (`curl`, PromQL/TraceQL/LogQL).
Все версии образов закреплены в `infrastructure/docker-compose.yaml`
(профиль `monitoring`, без `latest`).

## Состав

| Сервис | Версия | Назначение | Порт (хост) |
|---|---|---|---|
| Mimir | 2.16.0 | Хранение метрик, Prometheus-совместимый API (`/prometheus`), ruler алертов | 127.0.0.1:9090 |
| Tempo | 2.8.0 | Хранилище трейсов (OTLP-in, filesystem), TraceQL | 127.0.0.1:3200 |
| Loki | 3.5.0 | Хранилище логов (single-binary, filesystem), LogQL | 127.0.0.1:3100 |
| OTel Collector | 0.162.0 | Приём OTLP от приложения → batch → Tempo | — |
| Alloy | v1.12.2 | Сбор логов приложения из docker json-file → Loki; скрейп метрик app:8081 → remote_write в Mimir | — |

## Запуск

```bash
make obs-up      # стек приложения + наблюдаемость
make obs-down    # остановить (тома целы)
make obs-alerts  # текущие состояния алертов Mimir
make obs-reload  # перечитать правила алертов после правок
```

Креды скрейпа (`METRICS_USER` и `METRICS_PASSWORD` из `infrastructure/.env`)
приходят в контейнер alloy env-файлом; `config.alloy`
читает их через `sys.env()` для basic_auth при скрейпе `app:8081` — на хосте
файлов с секретами не остаётся.

## Метрики, трейсы, логи из консоли

```bash
# список всех метрик скрейпа (Prometheus-совместимый API Mimir)
curl -s 'localhost:9090/prometheus/api/v1/targets' | head -c 400
# PromQL-запрос
curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode \
  'query=rate(http_server_requests_seconds_count[5m])'
# трейс по traceId
curl -s "localhost:3200/api/trace/$TRACE_ID"
# логи приложения (LogQL)
curl -s -G http://localhost:3100/loki/api/v1/query_range --data-urlencode \
  'query={container_label_com_docker_compose_service="app"}' | head -c 400
```

Группировки панелей прежнего дашборда («RealHelpDesk — Обзор») соответствуют
разделам [docs/observability/metrics.md](../../docs/observability/metrics.md):
Service RED, бизнес, JVM, БД и фон — метрики там же, запросы — в runbooks.

## Алерты

Правила — `mimir/rules/anonymous/realhelpdesk.yml` (Prometheus-формат, монтируется ruler'у Mimir
read-only; раскладка local store `<dir>/<tenant>/<файл>`, tenant = `anonymous`). 8 алертов в 5 группах
(api, database, jvm, availability, security); каждому —
severity/summary/description/runbook_url. Runbooks: `docs/runbooks/*.md`. SLO: `docs/observability/slo.md`.

| Алерт | Порог | Runbook |
|---|---|---|
| ApiHighErrorRate | 5xx > 2% / 5 мин (critical) | `docs/runbooks/api-high-error-rate.md` |
| ApiHighLatencyP99 | p99 > 500 мс (SLO) / 10 мин | `docs/runbooks/api-high-latency.md` |
| DatabasePoolSaturation | active/max > 90% / 2 мин | `docs/runbooks/db-pool-saturation.md` |
| JvmHeapHigh | heap > 90% / 10 мин | `docs/runbooks/jvm-heap-high.md` |
| JvmGcOverheadHigh | GC > 10% времени / 10 мин | `docs/runbooks/jvm-gc-overhead.md` |
| AppUnreachable | `up == 0` / 2 мин (critical) | `docs/runbooks/app-unreachable.md` |
| NoNewTickets | нет заявок 2 ч в рабочее время | `docs/runbooks/no-new-tickets.md` |
| AuthFailuresSpike | failure > 6/мин / 5 мин | `docs/runbooks/auth-failures-spike.md` |

> В Boot 4.1 пул экспортируется как `jdbc_connections_*` — метрик `hikaricp_connections_pending/timeout`
> в скрейпе нет; вместо pending используется насыщение пула (см. runbook db-pool-saturation).

Проверка и перезагрузка правил:

```bash
# синтаксис (promtool из образа, без локальной установки)
docker run --rm -v $PWD/infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml:/alerts.yml:ro \
  --entrypoint promtool prom/prometheus:v3.15.0 check rules /alerts.yml
# перечитать правила после правок (рестарт контейнера) — или make obs-reload
make obs-reload
# текущие состояния алертов — или make obs-alerts
curl -s localhost:9090/prometheus/api/v1/alerts
```

## Потоки данных

```
приложение ──OTLP (traces)──▶ otel-collector ──▶ tempo
приложение ──stdout json──▶ alloy ──▶ loki
приложение ──HTTP Basic (метрики, management-порт 8081)──▶ alloy ──remote_write──▶ mimir
запросы     ──PromQL──▶ mimir:9090/prometheus │ TraceQL──▶ tempo:3200 │ LogQL──▶ loki:3100
```

Без наблюдаемости (обычный `make up`) endpoint — `localhost:4317` (дефолт
compose-интерполяции), экспорта трейсов нет (соединение отклоняется, приложение
работает). `make obs-up` и `make up-load`/`make load-smoke` передают
`OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces` инлайном, поэтому
в комбинации «obs + load» endpoint'ы совпадают — экспорт трейсов не ломается
при пересоздании app ни одной из команд. Постоянный адрес collector'а для всех
запусков — `OTLP_TRACING_ENDPOINT` в `infrastructure/.env`.

## Как найти трейс по traceId из лога

1. Возьмите `traceId` (32 hex) из строки лога приложения: колонка после `role`
   (`… | INFO | … | <traceId> | <spanId> | <logger> | <msg>`); там же его отдаёт `make logs`.
2. Запросите трейс в Tempo: `curl -s "localhost:3200/api/trace/$TRACE_ID"` —
   JSON со списком спанов (operational name, duration, теги ресурса).
3. Поиск по признакам — TraceQL: `curl -s -G localhost:3200/traceql/search
   --data-urlencode 'q={ .http.status_code >= 500 }' --data-urlencode 'limit=20'`.
4. Логи того же запроса: LogQL `|~ "<traceId>"` (пример выше) — связка
   лог ↔ трейс держится на одном traceId, отдельного хранилища связей нет.

Связка метрики → трейс: в Tempo включён serviceMap (через Mimir); exemplars в скрейпе
не включены — переход «метрика → конкретный trace» пока недоступен (см. «Известные ограничения»).

## Трассировка: сэмплинг

- Локально доля запросов задаётся `TRACING_SAMPLING_PROBABILITY` (`infrastructure/.env`, по умолчанию 1.0).
- В `otel-collector/config-prod.yaml` — пример прод-конфига с хвостовым сэмплингом: ошибки и трейсы
  дольше 1 s сохраняются целиком, остальное — 10%. Подключается монтированием файла поверх
  `config.yaml` в prod-оверлее.

## Известные ограничения

- **UI нет**: дашборды и графики не строятся — только API и runbook-запросы. При необходимости
  дашборды поднимают отдельно (внешний Grafana или Prometheus+UI) с теми же источниками:
  Mimir `/prometheus`, Tempo `:3200`, Loki `:3100`.
- **Exemplars не включены**: для `percentiles-histogram` в Micrometer exemplars требуют
  OpenTelemetry-трейсинг в том же потоке; переход с метрики на конкретный trace работает через
  Tempo serviceMap и логи, а не через exemplars.
- **Loki парсит текстовые логи** (паттерн `logback-spring.xml`, а не JSON): traceId попадает
  в вывод колонкой лога и ищется подстрокой в LogQL; при смене паттерна логов поправьте
  `alloy/config.alloy` и примеры запросов в этой документации.
- **Alloy ходит в docker.sock от root** — локальный dev-стенд; в проде заменить на Alloy с
  read-only сокетом и без root.
- **Retention**: Tempo 48 h (`tempo/tempo.yaml`), Loki 7 d (`loki/loki.yaml`), Mimir 15 d
  (`mimir/mimir.yaml`, compactor; как дефолт прежнего Prometheus).

## Полезные проверки

```bash
# скрейп дошёл в Mimir: up=1 и бизнес-метрики (Prometheus-совместимый API)
curl -s 'localhost:9090/prometheus/api/v1/query?query=up'
# трейсы в Tempo
curl -s http://localhost:3200/ready
# Loki готов
curl -s http://localhost:3100/ready
# логи приложения в Loki
curl -s -G http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={container_label_com_docker_compose_service="app"}' | head -c 400
```

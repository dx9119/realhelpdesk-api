# Инфраструктура наблюдаемости

Стек для локальной разработки: метрики и трейсы. UI не поднимается —
все сигналы доступны HTTP-API бэкендов (`curl`, PromQL/TraceQL).
Все версии образов закреплены в `infrastructure/docker-compose.yaml`
(профиль `monitoring`, без `latest`).

## Состав

| Сервис | Версия | Назначение | Порт (хост) |
|---|---|---|---|
| Mimir | 2.16.0 | Хранение метрик, Prometheus-совместимый API (`/prometheus`), ruler алертов | 127.0.0.1:9090 |
| Tempo | 2.8.0 | Хранилище трейсов (OTLP-in, filesystem), TraceQL | 127.0.0.1:3200 |
| OTel Collector | 0.162.0 | Приём OTLP от приложения → batch → Tempo | — |
| Alloy | v1.12.2 | Скрейп метрик app:8081 → remote_write в Mimir | — |

## Запуск

```bash
make dev-run     # dev-контур: стек приложения + наблюдаемость (monitoring входит)
make dev-down    # остановить инфраструктуру (тома целы)
```

Креды скрейпа (`METRICS_USER` и `METRICS_PASSWORD` из `infrastructure/.env`)
приходят в контейнер alloy env-файлом; `config.alloy`
читает их через `sys.env()` для basic_auth при скрейпе `app:8081` — на хосте
файлов с секретами не остаётся.

## Метрики и трейсы из консоли

```bash
# список всех метрик скрейпа (Prometheus-совместимый API Mimir)
curl -s 'localhost:9090/prometheus/api/v1/targets' | head -c 400
# PromQL-запрос
curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode \
  'query=rate(http_server_requests_seconds_count[5m])'
# трейс по traceId
curl -s "localhost:3200/api/traces/$TRACE_ID"
```

Группировки панелей прежнего дашборда («RealHelpDesk — Обзор») соответствуют
разделам [docs/observability/metrics.md](../../docs/observability/metrics.md):
Service RED, бизнес, JVM, БД и фон — метрики там же, запросы — в runbooks.

## Алерты

Правила — `mimir/rules/anonymous/realhelpdesk.yml` (Prometheus-формат, монтируется ruler'у Mimir
read-only; раскладка local store `<dir>/<tenant>/<файл>`, tenant = `anonymous`). 9 алертов в 6 группах
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
| EmailTemplateSentFailures | доля неудач > 1% / 5 мин | `docs/runbooks/email-template-sent-failures.md` |

> В Boot 4.1 пул экспортируется как `jdbc_connections_*` — метрик `hikaricp_connections_pending/timeout`
> в скрейпе нет; вместо pending используется насыщение пула (см. runbook db-pool-saturation).

Проверка и перезагрузка правил:

```bash
# синтаксис (promtool из образа, без локальной установки)
docker run --rm -v $PWD/infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml:/alerts.yml:ro \
  --entrypoint promtool prom/prometheus:v3.15.0 check rules /alerts.yml
# перечитать правила после правок (рестарт контейнера)
docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.obs.yaml \
  --profile infra --profile local-tools --profile monitoring restart mimir
# текущие состояния алертов
curl -s localhost:9090/prometheus/api/v1/alerts
```

## Потоки данных

```
приложение ──OTLP (traces)──▶ otel-collector ──▶ tempo
приложение ──HTTP Basic (метрики, management-порт 8081)──▶ alloy ──remote_write──▶ mimir
приложение ──stdout (терминал / лог-файл)──▶ grep <traceId>
запросы     ──PromQL──▶ mimir:9090/prometheus │ TraceQL──▶ tempo:3200
```

Логи приложения пишутся в stdout процесса, запустившего `make run-local`.
Alloy логи не собирает — контейнера приложения нет. Для поиска по логам
перенаправляйте вывод: `make run-local 2>&1 | tee app.log`, затем
`grep "<traceId>" app.log`.

Адрес Collector'а для хостового запуска — `http://localhost:4318/v1/traces`
(HTTP OTLP): он задан дефолтом в `application-dev.properties`, поэтому при
`make dev-run` + `make run-local` трейсы уходят в Tempo без переменных
окружения. Без `monitoring`-профиля соединение отклоняется — приложение
работает, экспорта нет. Переопределение — `OTLP_TRACING_ENDPOINT` в
`infrastructure/.env` (например, `http://otel-collector:4318/v1/traces` для
запуска приложения внутри compose-сети); `scripts/dev/load-env.sh`
экспортирует тот же loopback-адрес, если переменная не задана.

## Как найти трейс по traceId из лога

1. Возьмите `traceId` (32 hex) из строки лога приложения: колонка после `role`
   (`… | INFO | … | <traceId> | <spanId> | <logger> | <msg>`).
2. Запросите трейс в Tempo: `curl -s "localhost:3200/api/traces/$TRACE_ID"` —
   JSON со списком спанов (operational name, duration, теги ресурса).
3. Поиск по признакам — TraceQL (обязательны `start`/`end`: без окна Tempo
   ищет только в живом ingester, блоки на диске не попадают в выдачу):
   `curl -s -G localhost:3200/api/search --data-urlencode 'q={ .http.status_code >= 500 }'
   --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)"
   --data-urlencode 'limit=20'`.
4. Логи того же запроса: `grep "<traceId>" app.log` — связка
   лог ↔ трейс держится на одном traceId.

Связка метрики → трейс: в Tempo включён serviceMap (через Mimir); exemplars в скрейпе
не включены — переход «метрика → конкретный trace» пока недоступен (см. «Известные ограничения»).

## Трассировка: сэмплинг

- Локально доля запросов задаётся `TRACING_SAMPLING_PROBABILITY` (`infrastructure/.env`, по умолчанию 1.0).

## Известные ограничения

- **UI нет**: дашборды и графики не строятся — только API и runbook-запросы. При необходимости
  дашборды поднимают отдельно (внешний Grafana или Prometheus+UI) с теми же источниками:
  Mimir `/prometheus`, Tempo `:3200`.
- **Exemplars не включены**: для `percentiles-histogram` в Micrometer exemplars требуют
  OpenTelemetry-трейсинг в том же потоке; переход с метрики на конкретный trace работает через
  Tempo serviceMap и логи, а не через exemplars.
- **Логи не агрегируются**: приложение живёт на хосте, пишет в stdout терминала —
  alloy не собирает логи. Для поиска по логам перенаправляйте вывод
  (`make run-local 2>&1 | tee app.log`).
- **Retention**: Tempo 48 h (`tempo/tempo.yaml`), Mimir 15 d
  (`mimir/mimir.yaml`, compactor; как дефолт прежнего Prometheus).

## Полезные проверки

```bash
# скрейп дошёл в Mimir: up=1 и бизнес-метрики (Prometheus-совместимый API)
curl -s 'localhost:9090/prometheus/api/v1/query?query=up'
# трейсы в Tempo
curl -s http://localhost:3200/ready
```

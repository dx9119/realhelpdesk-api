# Наблюдаемость RealHelpDesk

Руководство по метрикам, трейсам и логам: архитектура, правила, отладка.

## Архитектура

Три сигнала, один стек (`make obs-up`; состав и версии — `infrastructure/observability/README.md`):

| Сигнал | Что | Куда | Доступ |
|---|---|---|---|
| Метрики | Micrometer → `/actuator/prometheus` (management-порт 8081, HTTP Basic) | Mimir | `curl -u … localhost:9090/prometheus` |
| Трейсы | Micrometer Tracing (OTel-мост) → OTLP → Collector → Tempo | Tempo | `curl -s localhost:3200/traceql/search` (TraceQL) |
| Логи | Logback-паттерн с `traceId`/`spanId` → stdout → Alloy → Loki | Loki | `curl -s localhost:3100/loki/api/v1/query` (LogQL) |

Приложение не зависит от стека: без `obs-up` экспорта трейсов нет (соединение отклоняется), метрики
живут на management-порту, логи пишутся в stdout как обычно.

### Точки наблюдения в коде

- HTTP-сервер: `ServerHttpObservationFilter` (Boot) → `http.server.requests` с конвенцией
  `HttpServerRequestObservationConvention` (тег `uri` — паттерн маршрута, не raw-URL).
- Бизнес-слой: `BusinessMetrics` (метрики) и `Observation.createNotStarted(...)` в сервисах:
  `ticket.create`, `ticket.status.change`, `mail.send`, `attachment.upload/download/delete`.
- Аутентификация: `AuthMetrics` → `auth.attempts{type,result}`.
- БД/фон: `hibernate.query.executions/statements`, `jdbc.connections_*`, `executor.*`,
  `tasks.scheduled.*`; Liquibase-миграции — `LiquibaseMigrationTimingBeanPostProcessor`.
- Здоровье: `HealthIndicator`'ы (БД, почта, MinIO) на `/actuator/health`.

## Каталог метрик

Полная таблица бизнес-метрик, теги, семантика, deny-списки и MeterFilter'ы —
[metrics.md](metrics.md). SLO и burn-rate — [slo.md](slo.md). Алерты и runbooks — ниже.

## Как добавить новую метрику

1. **Имя и тип.** Доменное имя без ПДн: `domain.event` (`Counter` событий), `Timer` длительностей,
   `Gauge` текущих значений. Теги — только конечные enum/группы; userId/ticketId/email/URL — никогда
   ( MeterFilter в `ObservabilityConfiguration` срежет их, но это защита, не приглашение).
2. **Место.** Метод в `BusinessMetrics` (паттерн «счётчик после коммита»: вызывать после
   `@Transactional`-метода доменного слоя) или отдельный компонент для Gauge (пример —
   `TicketsOpenGauge`: `AtomicLong`, обновление по `@Scheduled`, слабая ссылка Micrometer).
3. **Вызов.** Инкремент на успешной бизнес-операции; ошибки и откаты не считать
   (`mail.sent` — сразу после приёма SMTP, стоп-лист/лимиты не попадают).
4. **Теги.** `lowCardinalityKeyValue` только для enum/групп; `description` обязателен.
5. **Тест.** `BusinessMetricsTest` (SimpleMeterRegistry): имя, тип, теги, пропуск null/некорректных
   входов; для инкремента на вызове — тест сервиса.
6. **Документация.** Строка в таблицу [metrics.md](metrics.md).
7. **Критичный путь?** Алерт в `infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml` + runbook в
   `docs/runbooks/` с готовым PromQL-запросом к Mimir.

## Правила кардинальности

- **Теги** — конечные enum'ы (`TicketStatus`, `NotificationEvent`), группы (`content_type` — часть
  MIME до `/`), результаты (`success`/`failure`). Запрещённые значения (userId, ticketId, email,
  sessionId, token, password, полный URL) срезает `ignoreTags` в `ObservabilityConfiguration`;
  регрессия — `HttpObservabilityTest.prohibitedTagsAreAbsent`.
- **URI** — только паттерн маршрута (`/api/v1/tickets/{id}`), потолок 100 уникальных значений на
  `http.server.requests`, сверху DENY.
- **Deny-списки** — шумные/вводящие в заблуждение биндеры отключены свойствами
  `management.metrics.enable.*` (томкат-сессии без сессий, Hibernate-кэш без L2, диски хоста);
  актуальный список и обоснования — [metrics.md](metrics.md).
- **Бюджет** — цель ≤ 3000 series на инстанс; после шага 10 — 56 семейств / 92 series в покое.

## Как отлаживать по traceId

1. Возьмите `traceId` (32 hex) из строки лога: колонки `… | <traceId> | <spanId> | <logger> | <msg>`.
2. **Трейсы** — `curl -s localhost:3200/traceql/search` (TraceQL): вставить traceId → waterfall спанов.
3. Или **Логи** — `curl -s "localhost:3100/loki/api/v1/query?query={container_label_com_docker_compose_service=\"app\"} |= \"<traceId>\""` →
   ссылка **TraceID** в колонке слева → переход к трейсу.
4. Из трейса — «Logs for this span» (tracesToLogs настроен на тот же traceId).

Детали потоков данных и конфигурации — `infrastructure/observability/README.md`.

## Как работать с алертами

Правила — `infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml` (8 алертов в 5 группах, каждому —
severity/summary/description/runbook_url; их оценивает ruler Mimir). SLO — [slo.md](slo.md).

| Алерт | Порог | Runbook |
|---|---|---|
| ApiHighErrorRate | 5xx > 2% / 5 мин (critical) | [runbooks/api-high-error-rate.md](../runbooks/api-high-error-rate.md) |
| ApiHighLatencyP99 | p99 > 500 мс (SLO) / 10 мин | [runbooks/api-high-latency.md](../runbooks/api-high-latency.md) |
| DatabasePoolSaturation | active/max > 90% / 2 мин | [runbooks/db-pool-saturation.md](../runbooks/db-pool-saturation.md) |
| JvmHeapHigh | heap > 90% / 10 мин | [runbooks/jvm-heap-high.md](../runbooks/jvm-heap-high.md) |
| JvmGcOverheadHigh | GC > 10% времени / 10 мин | [runbooks/jvm-gc-overhead.md](../runbooks/jvm-gc-overhead.md) |
| AppUnreachable | `up == 0` / 2 мин (critical) | [runbooks/app-unreachable.md](../runbooks/app-unreachable.md) |
| NoNewTickets | нет заявок 2 ч в рабочее время | [runbooks/no-new-tickets.md](../runbooks/no-new-tickets.md) |
| AuthFailuresSpike | failure > 6/мин / 5 мин | [runbooks/auth-failures-spike.md](../runbooks/auth-failures-spike.md) |

```bash
# синтаксис правил (promtool из образа, без локальной установки)
docker run --rm -v $PWD/infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml:/alerts.yml:ro \
  --entrypoint promtool prom/prometheus:v3.15.0 check rules /alerts.yml
# перечитать правила после правок — или make obs-reload
docker compose -f infrastructure/docker-compose.yaml \
  --profile infra --profile dev --profile app --profile monitoring restart mimir
# текущие состояния алертов — или make obs-alerts
curl -s localhost:9090/prometheus/api/v1/alerts
```

Алгоритм: сработал алерт → открыть runbook → выполнить диагностические шаги → устранить причину →
закрыть инцидент с заметкой в runbook, если шагов не хватило.

## Итоги внедрения

### Что внедрено

- Management-порт 8081 с Basic-скрейпом; бизнес-метрики (заявки, вложения, письма, аутентификация),
  SLO-бакеты TTFR/решения, gauge открытых заявок; защита кардинальности (155 → 56 семейств).
- Observation-конвенции HTTP, трейсы сервисного слоя (`ticket.*`, `mail.send`, `attachment.*`),
  Micrometer Tracing + OTel-мост, маскирование ПДн в логах, колонки `traceId`/`spanId`.
- Полный стек `make obs-up`: Mimir, Tempo, OTel Collector, Loki, Alloy;
  8 алертов, 8 runbooks, 3 SLO.
- Тесты: `HttpObservabilityTest`, `DbObservabilityTest`, `ObservabilityConfigurationTest`,
  `ActuatorSecurityTest`, `LogCorrelationAndMaskingTest`, `ObservabilityContextTest`,
  `TracingSmokeIT`; всего 569 (`mvn verify`), юнит-часть — 544 (`mvn test`).
- Финальные испытания на живом стеке: load-smoke k6 (317 запросов, 100% checks, все пороги
  в допуске), бизнес-метрики в Mimir (Prometheus-совместимый API) после трафика, сквозная цепочка
  лог → Loki → Tempo по traceId (6 спанов на запрос), 8/8 правил алертов загружены.

### Что осознанно не внедрено

- **Exemplars** — переход «метрика → конкретный trace» идёт через Tempo serviceMap и логи.
- **Tail sampling** — включён как прод-пример (`otel-collector/config-prod.yaml`), локально
  probability-сэмплинг.
- **JSON-логи** — сохранён текстовый паттерн (Loki парсит его regex-ом в derived-поле).
- **Профилирование** (JFR/async-profiler) и **burn-rate алерты по SLO** — не внедрялись.

### Известные ограничения (совместимость с Boot 4)

- Пул БД экспортируется как `jdbc_connections_*` (тег `name="dataSource"`); `hikaricp_*` и метрики
  pending/timeout в этом контуре отсутствуют — алерт DatabasePoolSaturation использует насыщение.
- `Observation.Context.getParent()` отсутствует — parent доступен как `getParentObservation()`
  (`ObservationView`).
- В тестовом профиле `management.opentelemetry.enabled=false`: дерево наблюдений проверяется
  статическим `ObservationHandler`-коллектором (`TracingSmokeIT`), реальный OTLP-экспорт — только
  в `obs-up`-контуре.
- `promtool` не принимает `vector(hour())` — фильтры рабочего времени заданы цепочкой
  `hour() >= 9 < 18`.
- PrometheusNaming срезает суффикс `.created` у meter-имён (OpenMetrics резервирует `_created`):
  бизнес-счётчик `tickets.created` экспортируется как `tickets_total`, не `tickets_created_total`
  (см. [metrics.md](metrics.md)).

### Рекомендации на следующий этап

1. **Tail sampling в прод-оверлее** — подключить `infrastructure/observability/otel-collector/config-prod.yaml`
   монтированием поверх `config.yaml`: ошибки и трейсы > 1 s целиком, остальное 10%.
2. **Exemplars** — после стабилизации OTel-моста: `publishPercentileHistogram` + exemplars на
   `http.server.requests`, переход «метрика → trace» из Mimir.
3. **Профилирование** — JFR в контейнере (`-XX:StartFlightRecording`) для CPU/alloc, async-profiler
   для flame graphs на инцидентах высокой латентности.
4. **Burn-rate алерты по SLO** — многоволновые (fast/slow burn) вместо простых порогов
   `ApiHighLatencyP99`; recording rules для error budget.
5. **Аннотации деплоев** — запрос к Mimir или метрике `process.uptime`
   (рестарт = deploy) для корреляции регрессий с релизами.

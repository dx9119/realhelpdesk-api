# SLO RealHelpDesk

SLO — цели по SLI, измеряемым метриками из `docs/observability/metrics.md`.
Окно ошибок: скользящие 30 дней. Алерты на бюджет ошибок: `infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml`.

## SLO 1. Доступность API

| | |
|---|---|
| **SLI** | Доля HTTP-ответов не-5xx: `1 - sum(rate(http_server_requests_seconds_count{status=~"5.."}[30d])) / sum(rate(http_server_requests_seconds_count[30d]))` |
| **Цель** | ≥ 99.9% (допуска ~43 мин недоступности/мес) |
| **Окно** | 30 дней, пересчёт каждые 5 минут |
| **Сигнал** | Алерт `ApiHighErrorRate` (> 2% за 5 мин — раннее предупреждение, не нарушение самого SLO) |

Учитываются все ответы `http.server.requests`; uri `NOT_FOUND`/`UNKNOWN` (шум маршрутизатора)
включены — это честный доступный трафик. Регламент: нарушение SLO за месяц — разбор
постфактум (postmortem), неотъемлемая часть которого — корреляция с логами/трейсами.

## SLO 2. Латентность создания заявки

| | |
|---|---|
| **SLI** | `ticket.create` (наблюдение сервисного слоя): p95 |
| **Цель** | p95 ≤ 500 мс, p99 ≤ 1 с |
| **Окно** | 7 дней (операционная, короче бизнес-SLO — быстрее фидбек) |
| **Сигнал** | PromQL `histogram_quantile(0.95, sum(rate(ticket_create_seconds_bucket[5m])) by (le))` (Mimir); косвенно алерт `ApiHighLatencyP99` (HTTP p99 > 500 мс) |

Почему отдельно от HTTP p99: создание заявки — единственный критичный путь записи
с вложением (MinIO) и транзакцией; его деградация бьёт по пользователю сильнее
среднего GET. SLI считается из `ticket_create_seconds_bucket` — гистограмма включена
для этого наблюдения явно (см. metrics.md).

## SLO 3. Время первого ответа (бизнес)

| | |
|---|---|
| **SLI** | `tickets.time.to.first.response`: доля заявок с TTFR ≤ 30 мин |
| **Цель** | ≥ 95% в рабочее время (пн–пт, 9–18 UTC) |
| **Окно** | 30 дней |
| **Сигнал** | PromQL по `tickets_time_to_first_response_seconds_bucket` (Mimir); нарушение — не технический алерт, а инцидент поддержки |

Это не технический, а сервисный SLO: технически система может быть здоровой
(`up == 1`, ошибок нет), но SLA поддержки нарушен. Считается из
`tickets_time_to_first_response_seconds_bucket`; включение рабочего времени
задаётся при агрегации (в PromQL — фильтр `hour()`/`day_of_week()`, как в `NoNewTickets`).

## Осознанные ограничения

- **Exemplars выключены** — переход «метрика → трейс» идёт через Tempo serviceMap и
  Loki по traceId, не через exemplars (см. `infrastructure/observability/README.md`).
- **Минутный уровень алертов**: `evaluation_interval: 15s`, но окна rate — 5 мин;
  мгновенные просадки < 5 мин в SLO не считаем.
- **Зависимости (MinIO, SMTP)**: отдельных SLO нет; их деградация ловится
  health-группами (`/actuator/health/minio`, `/actuator/health/smtp`) и метрикой
  `mail.sent{result="failure"}`. Кандидат для следующего этапа — SLO на доставку писем.

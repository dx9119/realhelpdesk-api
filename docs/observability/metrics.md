# Бизнес-метрики RealHelpDesk

Бизнес-метрики заявок, вложений и писем. Источник: `BusinessMetrics` и `TicketsOpenGauge` в `core/observability`. Все series при экспорте получают
общие теги `application`, `env`, `instance` из `management.metrics.tags`.

## Таблица метрик

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `tickets.created` | Counter | `priority` (enum `TicketPriority`), `access` (enum `TicketAccessStatus`) | Созданные заявки по приоритету и типу доступа |
| `tickets.status.transitions` | Counter | `from`, `to` (enum `TicketStatus`) | Переходы статусов заявок; переход в тот же статус не считается |
| `tickets.open` | Gauge | `status` (`OPEN`, `IN_PROGRESS`) | Открытые заявки на момент последнего обновления (не по scrape) |
| `tickets.time.to.first.response` | Timer + SLO-бакеты | — | Время от создания заявки до первого в ней сообщения |
| `tickets.time.to.resolution` | Timer + SLO-бакеты | — | Время от создания до закрытия заявки |
| `attachments.uploaded` | Counter | `content_type` (группа MIME) | Загруженные вложения по группе типа содержимого |
| `attachments.size` | DistributionSummary, `baseUnit=bytes` | `content_type` (группа MIME) | Суммарный размер загруженных вложений |
| `mail.sent` | Counter | `type` (enum `NotificationEvent`), `result` (`success`/`failure`) | Фактические попытки отправки письма |
| `auth.attempts` | Counter | `type`, `result` | Попытки аутентификации (шаг 4, `AuthMetrics`) |

Экспортируемые имена в Prometheus: `tickets_total` (Micrometer-имя `tickets.created`: конвенция
PrometheusNaming срезает суффикс `.created` — OpenMetrics резервирует `_created` вспомогательной
меткой, поэтому к `tickets` добавляется только `_total`), `tickets_status_transitions_total`,
`tickets_open`, `attachments_uploaded_total`, `attachments_size_bytes`, `mail_sent_total`,
`auth_attempts_total`; Timer получает суффикс `_seconds` автоматически
(`tickets_time_to_first_response_seconds_bucket/…_sum/…_count`).

## Теги и кардинальность

- Значения enum-тегов — имя enum'а как есть (`HIGH`, `CREATOR_AND_PORTAL_USERS`, `IN_PROGRESS`, `NEW_TICKET`), как в тегах наблюдений
  `ticket.status.change`; `result` — `success`/`failure`, как в `auth.attempts`; `content_type` — группа в нижнем регистре.
- Группа `content_type` — часть MIME-типа до `/` из конечного списка `image`, `text`, `application`, `video`, `audio`; всё остальное (включая
  null и `application/octet-stream`-подобные исключения без `/`) — `other`. Имя файла в теги не попадает.
- Email, userId, ticketId, recipient, полный URL и тексты ошибок в теги не попадают — это проверяет и регрессия
  `HttpObservabilityIT.prohibitedTagsAreAbsent`.

## Семантика и принятые решения

- **`category` → `access`.** В домене нет «категории» заявки; вместо второго тега из плана используется конечный enum
  `TicketAccessStatus` (`ALL_USERS`, `CREATOR_AND_PORTAL_USERS`) — признак аудитории заявки, не высококардинальный.
- **Инкремент после коммита.** `saveTicket`/`saveMessage`/`saveAttachment` — `@Transactional`-методы доменного слоя, возвращающиеся после
  коммита, поэтому вызов метрики сразу за ними — это граница «после коммита»: откат или исключение раньше счётчик не завышает (аналог
  `@TransactionalEventListener(AFTER_COMMIT)` без публикации событий в домен). `mail.sent` считается сразу после приёма письма SMTP:
  стоп-лист и лимиты возвращаются до `deliver` и попытками не считаются.
- **Первый ответ.** `tickets.time.to.first.response` пишется для первого сообщения в заявке — той же семантики, что и доменный признак
  «без ответа» (`findOpenTicketIdsWithoutMessages…`: заявка без ответа = без сообщений, независимо от автора). Счётчик берётся до вставки
  нового сообщения (`countByTicketId`), поэтому вложения (они создаются сообщением) участвуют как первое сообщение.
- **Время решения** пишется при переходе в `CLOSED` из любого другого статуса; повторное закрытие (заявка переоткрывалась) пишется заново —
  каждое закрытие отдельное наблюдение распределения.
- **SLO-бакеты.** `first.response`: 1m, 5m, 30m, 2h, 8h, 24h; `resolution`: 1h, 6h, 24h, 72h, 168h. Бакеты заданы в коде
  (`Timer.Builder.serviceLevelObjectives`), `publishPercentileHistogram` не включался — в скрейпе появляются только фиксированные `_bucket`.
- **`tickets.open`.** Один count-запрос на статус раз в 30 секунд (`@Scheduled`), scrape значение только читает из памяти. Считаются только
  незакрытые статусы (`OPEN`, `IN_PROGRESS`): закрытые заявки «открытыми» не являются, у заявки всегда один из двух статусов, поэтому сумма
  series = числу открытых заявок. Gauge держит `AtomicLong` в компоненте (Micrometer ссылается на значение слабой ссылкой), JPA-сущности в
  значение не попадают. Ошибка БД в обновлении уходит в обработчик планировщика (лог ERROR), значения остаются прежними до следующего
  обновления.
- **`baseUnit`.** Указан только там, где есть физическая единица: `bytes` у `attachments.size` — PrometheusNamingConvention добавит суффикс
  (`attachments_size_bytes`); Timer экспортируется с `_seconds` без явного `baseUnit`. У счётчиков событий `baseUnit` намеренно не задан:
  конвенция добавила бы его к имени (`tickets_created_events_total`), исказив канонические имена из плана, а счётчик событий безразмерен.
  Description заполнен у каждой метрики.

## Защита от кардинальности (шаг 10)

Список отключений согласован по замеру `/actuator/prometheus` (155 семейств / 1422 series до шага 10; цель — до 3000 series на инстанс).

**MeterFilter'ы** (`ObservabilityConfiguration`):

- `maximumAllowableTags` для `http.server.requests` и `http.client.requests` по `uri` — потолок 100 уникальных значений, сверху DENY
  (для server-цепочки дублируется свойством `management.metrics.web.server.max-uri-tags`).
- `ignoreTags` для опасных тегов (`userId`, `email`, `ticketId`, `sessionId`, `token`, `password` и т.п.) — срезает их у любой метрики, даже
  если новый код добавит их в tag list по ошибке.
- `deny` по именам: `disk.free`, `disk.total` (диск контейнера = хостовая ФС, вводит в заблуждение), `jvm.buffer.*`, `hibernate.flushes`,
  `hibernate.sessions.*`, `hibernate.optimistic.failures`.

**Отключённые биндеры** (`management.metrics.enable.*`):

| Свойство | Обоснование |
|---|---|
| `tomcat=false` | Сессии не используются (stateless JWT) — `tomcat_sessions_*` всегда шум |
| `hibernate.cache=false` | L2-кэш не подключён — метрики всегда нули |
| `hibernate.entities/collections=false` | Счётчики CRUD сущностей дублируют `hibernate.query.executions` и `hibernate.statements` |
| `spring.security.filterchains=false` | ~30 семейств before/after-таймеров фильтров; 401 и ошибки доступа уже видны в `http.server.requests` и `auth.attempts` |
| `spring.security.http.secured.requests=false`, `spring.security.authorizations=false` | Дублируют `http.server.requests` (uri, status, role) |
| `spring.data.repository=false` | Таймеры по каждому методу репозитория дублируют `hibernate.query.executions` |

**Сохранены**: `http.server.requests` (гистограмма + SLO), `auth.attempts`, бизнес-метрики шага 8, `mail.*`, `ticket.create`,
`hibernate.query.executions/statements/transactions`, `jdbc.connections`, `jvm.gc/memory/threads`, `logback_events` (рост ошибок),
`process`/`system`, `executor.*`, `tasks.scheduled.*`.

**Пул планировщика.** Boot автоматически биндит `ThreadPoolTaskScheduler` как `executor.*{name=taskScheduler}` — отдельный биндер не нужен.

**Замер после шага 10** (холодый старт, без трафика): 56 семейств / 92 series; `executor_*` присутствуют, отключённые семейства в скрейпе
отсутствуют. Прогон `user_journey` (2 VU, 1 минута, 317 запросов) добавляет HTTP/business-метрики — суммарно порядка 120–150 series.

**Тесты**: `ObservabilityConfigurationTest` (deny/ignoreTags/потолок uri на SimpleMeterRegistry), `HttpObservabilityIT`
(отключённые метрики отсутствуют, `executor.*{name=taskScheduler}` привязан, серия запросов с разными ID не растит число uri-значений).

## Проверка вручную

```bash
# после логина и создания заявки/сообщения/вложения и отправки письма
# (креды скрейпа — METRICS_USER и METRICS_PASSWORD из infrastructure/.env)
curl -s -u "$METRICS_USER:$METRICS_PASSWORD" localhost:8081/actuator/prometheus \
  | grep -E 'tickets_total|tickets_status_transitions|tickets_open|tickets_time_to|attachments_|mail_sent'
```

Ожидаемо: `tickets_total{priority="MEDIUM",…}` растёт при создании заявки, `tickets_time_to_first_response_seconds_count` появляется на
первом сообщении, `tickets_open{status="OPEN"}` обновляется не чаще раза в 30 секунд, `attachments_size_bytes` растёт на загрузках.

## Тесты

- `BusinessMetricsTest` — все метрики, теги, SLO-бакеты, пропуск null/некорректных входов.
- `TicketsOpenGaugeTest` — регистрация series, обновление из репозитория, отсутствие запросов при чтении.
- `TicketManageServiceTest`, `MessageManageServiceTest`, `AttachmentManageServiceTest`, `EmailDeliveryServiceTest` — инкременты на вызовах
  сервисов; `ActuatorSecurityIT` — `tickets_open` в скрейпе.

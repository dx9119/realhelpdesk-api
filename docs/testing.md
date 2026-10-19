# Тесты и проверки

Все тесты проводятся только в dev-среде. Юнит- и интеграционные — на хосте,
без Docker, в тестовой конфигурации `src/test/resources/application.properties`;
e2e — только на поднятом dev-стеке (`make dev-run` или `make up`). В других
средах тесты не запускаются.

| Команда | Что делает |
|---|---|
| `make test` | Юнит-тесты и срезы (`@DataJpaTest` на H2) |
| `make verify` | То же + интеграционные `*IT` (failsafe, GreenMail) |
| `make format` | Форматирование (`spotless:apply`) |
| `make check` | Проверки стиля (`spotless:check`, `checkstyle`) |
| `make openapi` | Снять OpenAPI-спеку с запущенного стека в `openapi.json` |
| `make openapi-types` | Сгенерировать TypeScript-типы (`npx openapi-typescript`) |
| `make dev-run` | Dev-среда: сборка и запуск стека в foreground (логи всех сервисов) |
| `make dev-test` | Dev-среда: `mvn verify` + все e2e-сценарии (нужен поднятый стек) |

## E2E-сценарии

Сценарии идут по живому стеку (`make up`) — все сразу через `make e2e-all`
или по одному:

| Команда | Сценарий | Скрипт |
|---|---|---|
| `make e2e-portal-access` | Права доступа к порталу | `scripts/e2e-portal-access.sh` |
| `make e2e-ratelimit` | Рейт-лимиты (ручной прогон; логика покрыта `RateLimitServiceTest`) | `scripts/e2e-ratelimit.sh` |
| `make e2e-jwt` | JWT-поток аутентификации | `scripts/e2e-jwt-flow.sh` |
| `make e2e-notifications` | In-app оповещения (long polling) | `scripts/e2e-notifications.sh` |
| `make e2e-portal-transfer` | Передача владения порталом | `scripts/e2e-portal-transfer.sh` |
| `make e2e-token-cleanup` | Фоновая очистка refresh-токенов по расписанию (доступ и батчи покрыты `OperatorTokenCleanupIT`) | `scripts/e2e-token-cleanup.sh` |
| `make e2e-keycloak` | Вход через Keycloak (профиль `keycloak`) | `scripts/e2e-keycloak.sh` |
| `make e2e-all` | Базовые сценарии подряд | `scripts/e2e-*.sh` |

Сценарии `e2e-portal-access`, `e2e-jwt`, `e2e-notifications`,
`e2e-portal-transfer` и `e2e-token-cleanup` ходят в cookie-эндпоинты
`/api/v1/auth/*`, то есть во внутренний JWT-контур: цели `make` перед прогоном
переключают приложение на профиль `dev` (без `keycloak`). Контур Keycloak
проверяет `e2e-keycloak` — он переключает профиль обратно на `dev,keycloak`
и работает с Bearer-токеном, выпущенным Keycloak.

В `e2e-all` не входит `e2e-ratelimit`: логика бакетов, 429 и `Retry-After`
целиком покрыта `RateLimitServiceTest`, а прогон тратит 56 последовательных
запросов. Цель `make e2e-ratelimit` остаётся для ручного прогона после
изменений в рейт-лимитах (см. [`rate-limits.md`](rate-limits.md)).

Скрипты создают тестовых пользователей и тратят рейт-лимиты — при повторных
прогонах может потребоваться `make restart`. `e2e-token-cleanup` дополнительно
занимает сроки токенов в `infrastructure/.env` до секунд, пересоздаёт контейнер
`app` и по завершении возвращает конфиг и контейнер (в том числе по `Ctrl+C`).

## Нагрузочное тестирование (k6)

Полный user journey под нагрузкой: каждый VU повторяет виток
«профиль → (ре)логин → портал → заявка → сообщение → список сообщений →
поиск → unread-count → long-poll уведомлений → refresh access»
с паузой `THINK_TIME` между итерациями. Скрипт —
`scripts/load/user-journey.js` (k6), обёртка — `scripts/load/run.sh`.

| Команда | Что делает |
|---|---|
| `make load` | Полный прогон: сидит пул пользователей и гоняет journey (10 VU, 5 минут) |
| `make load-smoke` | Короткий smoke: 2 VU, 1 минута |
| `make up-load` | Только поднять стек с load-оверлеем (k6 запускать вручную) |

Параметры: `TARGET_VUS`, `DURATION`, `POOL_SIZE` (≤10), `THINK_TIME`,
`WAIT_TIMEOUT_SEC` — через окружение, например `TARGET_VUS=30 DURATION=15m make load`.
`RESET_LIMITS=1` — перезапустить app перед прогоном (обнуляет in-memory
счётчики рейт-лимитов, если предыдущий прогон исчерпал окно логина 10/300с).

### Как устроено

- **Пул пользователей** — пул до 10 `load-pool-<RUN_ID>-pN@load.test` сидирует
  `run.sh` (регистрация + psql-верификация email — портал создаёт только
  `isEmailVerified=true`). VU распределяют пользователей по кругу
  (`VU1 → p1, VU2 → p2, …`), общий лимит регистрации не превышается.
- **Рейт-лимиты** — `make up-load` передаёт `TRUST_PROXY_HEADERS=true`
  инлайном: каждая k6-VU шлёт уникальный `X-Forwarded-For` и получает свой
  bucket рейт-лимитов. Без этого профиля все VU делят один `127.0.0.1` и
  логин упирается в лимит 10/300с. Включать **только локально** — за таким
  профилем нет прокси.
- **Совместимость с наблюдаемостью** — `up-load`/`load-smoke` передают
  `OTLP_TRACING_ENDPOINT` на collector инлайном: app пересоздаётся, и без
  этого ключа экспорт трейсов откатился бы на `localhost:4318/v1/traces` (дефолт
  compose-интерполяции), сломав связку с запущенным obs-стеком
  (`make obs-up`). Прогон метрик/трейсов во время load-теста: поднимите
  `make obs-up`, затем `make load-smoke` — смотрите метрики в Mimir
  (`localhost:9090/prometheus`) и `make obs-alerts`.
- **Cookie jar** — явная per-VU jar (k6 сбрасывает встроенную между
  итерациями): access-token из логина живёт между витками, re-login
  происходит только по 401.
- **Капча** выключена локально (`CAPTCHA_ENABLED=false` в
  `infrastructure/.env`, дефолт шаблона) — иначе регистрация/логин под
  нагрузкой не пройдут.

### Пороги

Падают с nonzero exit — прогон считается проваленным:

| Порог | Значение |
|---|---|
| `http_req_failed` | <1% |
| `checks` | >99% |
| `http_req_duration{search}` p95 | <500ms |
| `http_req_duration{ticket_create}` p95 | <1200ms |
| `http_req_duration{notifications_wait}` p95 | <6s (long-poll 4s) |

Эталонный результат (10 VU, 5 минут, 669 итераций, локально): 99.8% checks
зелёных, 0.4% HTTP-ошибок, ticket_create p95 ≈ 43ms, search p95 ≈ 8ms,
~15 RPS. Отчёт k6 (RPS, percentiles по endpoint'ам, пороги) печатается
в stdout.

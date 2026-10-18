# RealHelpDesk API

REST API системы управления заявками. Предоставляет функционал для работы с порталами (тематическими пространствами), заявками, сообщениями и вложениями.

## Возможности

- Регистрация, авторизация, аутентификация, верификация email, восстановление пароля.
- Порталы: создание, редактирование, удаление; публичный доступ и доступ по списку доверенных пользователей.
- Заявки: создание, статусы, приоритеты, удаление, поиск по заявкам.
- Формирование сообщений, прикрепление к заявке файловых вложений (MinIO, отдача с поддержкой HTTP `Range` для не стабильных сетей).
- Передача права владения порталом с подтверждением и полной историей состояний портала.
- Оповещения: email и in-app (SSE-канал, long polling — фолбэк).
- Ограничения: квоты пользователя, рейт-лимиты, капча.

## Технологии

| Слой | Технологии                                                             |
|---|------------------------------------------------------------------------|
| Язык / сборка | Java 21, Maven                                                         |
| Фреймворк | Spring Boot 4, Spring Security, Spring Data JPA, Spring Validation     |
| БД / миграции | PostgreSQL 18, Liquibase (`ddl-auto=validate`)                         |
| Аутентификация | Keycloak (OAuth2 Resource Server, профиль `keycloak` — по умолчанию) либо JWT (jjwt) в http-only cookies, refresh-токен хранится как SHA-256 хеш |
| Файлы | MinIO (метаданные в БД, содержимое — в бакете)                         |
| Почта | Spring Mail, шаблоны сообщений в `messages.properties`                 |
| Прочее | Lombok, Hibernate Validator, Spotless, Checkstyle                     |

## Быстрый старт

Проект запускается в Docker; Java 21 и Maven нужны только для разработки (тесты и сборка). Понадобятся Docker с Compose v2 и GNU Make — `make help` печатает список всех команд.

1. **Создать файл настроек.** Первый раз:

   ```bash
   cp infrastructure/.env.example infrastructure/.env
   ```

   Или сразу `make env`: синхронизирует `.env` с шаблоном — создаст файл, допишет недостающие ключи и обновит изменившиеся дефолты, не трогая ваши значения и секреты.

2. **Заполнить секреты** в `infrastructure/.env` — это пустые строки шаблона, без них стек не поднимется. Подробности в [`configuration.md`](docs/configuration.md).

3. **Поднять стек** — на выбор:

   - `make up` — в фоне: сборка образа и запуск всего;
   - `make dev-run` — в foreground: логи всех сервисов одним потоком, `Ctrl+C` — остановка;
   - `make up-deps` — только инфраструктура (БД, MinIO, smtp4dev, Keycloak) — для запуска приложения из IDEA на хосте.

4. **Проверить, что работает:**

   - `curl -sk https://localhost:8443/api/v1/health` → `200` — приложение живо;
   - `https://localhost:8443/swagger-ui.html` — Swagger UI.

5. **Попробовать API.** По умолчанию стек работает с Keycloak: получите токен в Keycloak и передайте его заголовком `Authorization: Bearer <token>`. Внутренний JWT-контур (после `make profile-local`) работает иначе: зарегистрируйте пользователя и подтвердите email — письмо с кодом придёт в smtp4dev.

6. **Работать с кодом:**

   - `make test` / `make verify` — юнит- и интеграционные тесты (поднятый стек не нужен);
   - `make format` / `make check` — форматирование и проверки стиля;
   - `make e2e-*` — e2e-сценарии на поднятом стеке, `make e2e-all` — все сразу;
   - `make apply-config` — применить изменённые конфиги к запущенному стеку, `make restart` — просто перезапуск приложения;
   - `make logs` — логи приложения, `make ps` — статус контейнеров, `make down` — остановить стек.

## Архитектура

Структура пакетов (`src/main/java/com/ukhanov/realhelpdesk`):

```
com.ukhanov.realhelpdesk
├── core — общая инфраструктурная часть, не зависящая от предметной области
│   ├── annotation, config, controller, exception
│   ├── filter, http, log, mail, observability, pagination
│   ├── security (accesscontrol, handler, keycloak, limiter, localjwt, ratelimit, user)
│   └── storage
├── domain — доменная модель: сущности, репозитории и доменные правила
│   ├── portal, ticket, message, attachment, notification
└── feature — функциональные модули: REST-слой по областям применения
    ├── portalmanager, ticketmanager, messagemanager
    ├── attachmentmanager, notificationmanager, usermanager
```

Ошибки HTTP-слоя централизованно преобразуются в RFC 7807-подобные ответы через `GlobalExceptionHandler`. Правила доступа к порталам и заявкам — в разделе [`access-control.md`](docs/access-control.md).

## Конфигурация

Приложение не хранит значений настроек в коде: каждая настройка — переменная окружения без дефолта. В `src/main/resources/application.properties` записана только схема — строки вида `${VAR}` без значения. Если переменная не задана, приложение не стартует и называет причину.

Подробнее: [`docs/configuration.md`](docs/configuration.md)

## Аутентификация

Контур аутентификации выбирается профилем `keycloak`: по умолчанию токены выпускает Keycloak, без профиля работает внутренний JWT-контур приложения (cookie `accessToken`, эндпоинты `/api/v1/auth/*`).

Подробнее: [`docs/authentication.md`](docs/authentication.md)

## Правила доступа

Все проверки выполняются на сервере в момент обработки запроса. Порталы поддерживают три уровня доступа: владелец, доверенный пользователь, публичный. Заявки имеют две политики доступа: `ALL_USERS` (для всех, кому доступен портал) и `CREATOR_AND_PORTAL_USERS` (только для автора и пользователей портала). Передача владения порталом проходит через подтверждение с фиксацией в истории.

Подробнее: [`docs/access-control.md`](docs/access-control.md)

## Оповещения

In-app оповещения доходят по двум каналам: основной — SSE (`/notifications/stream`), фолбэк — long polling (`/notifications/wait`). Оба канала используют общий курсор `afterId` и отдают одни и те же строки. Дополнительно о заявках уходят email-уведомления, а повторы напоминаний доставляет фоновый планировщик.

Подробнее: [`docs/notifications.md`](docs/notifications.md)

## Рейт-лимиты

Счётчик ведётся по IP и эндпоинту; при превышении возвращается `429` с заголовком `Retry-After`. Счётчики хранятся в памяти процесса.

Подробнее: [`docs/rate-limits.md`](docs/rate-limits.md)

## Наблюдаемость

Метрики (Micrometer → Mimir), трейсы (OTel → Tempo) и логи с `traceId`/`spanId` (→ Loki через Alloy). Стек поднимается вместе с приложением:

| Команда | Что делает |
|---|---|
| `make obs-up` | Стек + Mimir, Grafana, Tempo, Loki, Collector |
| `make obs-down` | Остановить наблюдаемость (тома целы) |
| `make obs-open` | Открыть Grafana в браузере |
| `make obs-reload` | Перечитать правила алертов Mimir |
| `make obs-alerts` | Текущие состояния алертов |

Метрики на management-порту 8081 (`/actuator/prometheus`, HTTP Basic). Сводный дашборд — Grafana → RealHelpDesk → Обзор.

Подробнее: [`docs/observability/overview.md`](docs/observability/overview.md)

## API

Описание всех эндпоинтов — [`docs/api.md`](docs/api.md). Машинная спека собирается из контроллеров: `GET /v3/api-docs`, UI — `/swagger-ui.html`.

```bash
make openapi          # снять OpenAPI-спеку в openapi.json
make openapi-types    # сгенерировать TypeScript-типы
```

## Тесты и проверки

Все тесты проводятся только в dev-среде. Юнит- и интеграционные — на хосте без Docker; e2e — только на поднятом стеке.

Подробнее: [`docs/testing.md`](docs/testing.md)

## Runbooks

Алгоритмы реагирования на алерты Grafana: [`docs/runbooks/`](docs/runbooks/)

# E2E-сценарии: разделение контуров аутентификации

## Вводная

Сейчас 7 e2e-скриптов в `scripts/` проверяют проект через cookie-аутентификацию
внутреннего JWT-контура (`SPRING_PROFILES_ACTIVE=dev`). Это покрывает
`WebSecurityConfiguration` (локальный выпуск токенов, refresh-ротация, captcha,
rate-limits, email-подтверждения и т.д.).

Параллельно проект поддерживает контур **Keycloak** (`SPRING_PROFILES_ACTIVE=dev,keycloak`
или `prod,keycloak`), где токены выпускает внешний OAuth2-сервер. Его проверяет
только **один** скрипт — `e2e-keycloak.sh`.

**Проблема:** `make e2e-all` (и `make dev-test`) не включает `e2e-keycloak` —
ключcloak-контур проверяется только отдельно. При изменениях в security-цепочке
Keycloak-сценарий может регрессировать незаметно.

## Текущие e2e-скрипты

| Скрипт | Контур | Что проверяет | Включён в e2e-all |
|---|---|---|---|
| `e2e-portal-access.sh` | JWT (cookie) | Роли портала, публичные/приватные настройки | ✅ |
| `e2e-portal-transfer.sh` | JWT (cookie) | Передача владения, история портала | ✅ |
| `e2e-jwt-flow.sh` | JWT (cookie) | Refresh-ротация, логаут, смена пароля | ✅ |
| `e2e-notifications.sh` | JWT (cookie) | SSE, long polling, настройки, повторы | ✅ |
| `e2e-token-cleanup.sh` | JWT (cookie) | Cron-очистка refresh-токенов | ✅ |
| `e2e-ratelimit.sh` | JWT (cookie) | Троттлинг по IP+endpoint (429) | ❌ (отдельная цель) |
| `e2e-keycloak.sh` | Keycloak (Bearer) | OAuth2 RS, роли, блокировка | ❌ (отдельная цель) |

## Целевая структура

Необходимо разделить e2e на два набора с явным названием контура в имени цели:

### `e2e-jwt-in-app` — внутренний JWT-контур (cookie)
`SPRING_PROFILES_ACTIVE=dev`

```
e2e-jwt-in-app:
  ├── e2e-portal-access
  ├── e2e-portal-transfer
  ├── e2e-jwt-flow
  ├── e2e-notifications
  ├── e2e-token-cleanup
  └── e2e-ratelimit
```

### `e2e-keycloak` — внешний OAuth2-контур (Bearer)
`SPRING_PROFILES_ACTIVE=dev,keycloak`

```
e2e-keycloak:
  ├── e2e-keycloak (существующий, без изменений)
  ├── e2e-portal-access-kc (новый)
  ├── e2e-portal-transfer-kc (новый)
  └── e2e-notifications-kc (новый)
```

**Что проверяет Keycloak-контур:**

1. **Аутентификация через Bearer-токен Keycloak** — `/users/profile`, `/portals`,
   `/notifications` работают с токеном, выданным Keycloak (direct access grant).

2. **Базовые CRUD** — создание портала, создание заявки, чтение портала —
   без cookie, через `Authorization: Bearer <token>`.

3. **Передача владения** — initiate/confirm/reject/cancel с паролем Keycloak.

4. **Уведомления** — SSE, long polling, backfill, настройки событий —
   без cookie, через Bearer-токен.

5. **Рейт-лимиты** — 429 по IP+endpoint (не зависит от контура аутентификации,
   но проверяется с токеном Keycloak).

**Что НЕ переносится в Keycloak-контур:**

- `e2e-jwt-flow` — проверяет refresh-ротацию, логаут, cookie, смену пароля
  через локальный поток. В Keycloak-контуре эти эндпоинты не создаются
  (`AuthController` помечен `@Profile("localjwt")`).

- `e2e-token-cleanup` — проверяет cron-очистку через инлайн-переменные,
  которые работают только в локальном JWT-контуре. В Keycloak-контуре
  refresh-токены не выдаются (токены выпущены Keycloak'ом).

## План работ

### Шаг 1. Общие утилиты
Создать `scripts/e2e-common.sh` — функции, общие для обоих контуров:
- `register_local` / `register_kc` — регистрация (local: POST /auth/register,
  Keycloak: POST /realms/.../protocol/openid-connect/token)
- `login_kc` — получение токена Keycloak
- `check` / `check_contains` — общие проверки
- `wait_mail` — ожидание письма в smtp4dev
- `sql` / `confirm_email_local` — общие БД и email-утилиты

### Шаг 2. Копирование + адаптация e2e-сценариев
Для каждого из 5 сценариев (портал-доступ, передача, уведомления, рейт-лимиты,
+ можно добавить e2e-access-kc — базовые CRUD с Bearer):
- Скопировать в `e2e-<name>-kc.sh`
- Заменить cookie-аутентификацию на `Authorization: Bearer`
- Убрать зависимости от локального JWT-потока (confirm email, refresh-ротация,
  логаут, смена пароля)
- Использовать `keycloak_token()` для получения токенов

### Шаг 3. Обновление Makefile
- Разделить `e2e-all` на `e2e-jwt-in-app` и `e2e-keycloak`
- `e2e-all` вызывает оба набора
- `dev-test` вызывает оба набора
- `e2e-ratelimit` и `e2e-keycloak` остаются отдельными целями

### Шаг 4. README
- Обновить раздел «Тесты и проверки» — описать разделение на два контура
- Указать, что `e2e-all` проверяет оба контура

## Риски и ограничения

1. **Keycloak в compose** — сервис `keycloak` профиля `local-tools` уже поднимается
   с `make up`. Direct access grant включён в realm'е.

2. **Лимиты регистрации** — Keycloak-скрипты создают пользователей через Keycloak
   API (не `/auth/register`), поэтому не тратят лимиты приложения.

3. **Email-подтверждения** — в Keycloak-контуре email-подтверждения не нужны
   (регистрация через Keycloak, `is_email_verified=true` из токена).

4. **Тестовые пользователи** — Keycloak-скрипты переиспользуют существующих
   пользователей из realm (`user@example.com`, `operator@example.com`),
   что быстрее и надёжнее.

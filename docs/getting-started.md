# Запуск проекта

Проект запускается в Docker; Java 21 и Maven нужны только для разработки
(тесты и сборка). Понадобятся Docker с Compose v2 и GNU Make — `make help`
печатает список всех команд.

Конфигурация стека живёт в `infrastructure/.env`; как устроены файлы настроек
и как менять значения — в [`configuration.md`](configuration.md).

## Подготовка (однократно)

1. Создать файл настроек:

   ```bash
   make env
   ```

   Цель синхронизирует `infrastructure/.env` с шаблоном
   `infrastructure/.env.example`: создаёт файл, дописывает недостающие ключи
   и обновляет изменившиеся дефолты, не трогая заданные вручную значения.

2. Заполнить секреты в `infrastructure/.env` — в шаблоне это пустые строки,
   без них стек не поднимется:

   | Секрет | Чем заполнить |
   |---|---|
   | `JWT_SECRET` | случайная строка от 32 символов |
   | `CAPTCHA_HMAC_SECRET` | случайная строка от 32 символов |
   | `DB_PASSWORD` | любой пароль PostgreSQL |
   | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | учётная запись MinIO |
   | `METRICS_PASSWORD` | пароль сервисной учётки скрейпа метрик |

   `KEY_STORE_PASS` в шаблоне уже имеет локальное значение; `MAIL_PASSWORD`
   нужен только для внешнего SMTP, `KEYCLOAK_ADMIN_PASSWORD` — только если
   стенд Keycloak доступен не только вам.

## Dev: полный стек в контейнерах

```bash
make up        # сборка образа и запуск всего в фоне
make dev-run   # то же в foreground: логи всех сервисов, Ctrl+C — остановка
```

Поднимаются приложение и инфраструктура (PostgreSQL, MinIO, smtp4dev,
Keycloak). Проверка:

- `curl -sk https://localhost:8443/api/v1/health` → `200`;
- `https://localhost:8443/swagger-ui.html` — Swagger UI
  (машинная спека — `GET /v3/api-docs`).

Dev-сервисы (в рабочем контуре их нет): smtp4dev — тестовый почтовый ящик
на `http://localhost:3000`, Keycloak — `http://localhost:8180`, PostgreSQL
`127.0.0.1:5432`, MinIO `127.0.0.1:9000`. Порты публикует
`docker-compose.override.yaml` только на loopback.

Первое обращение к API зависит от контура аутентификации
(по умолчанию — Keycloak, сравнение контуров в [`authentication.md`](authentication.md)):

- **Keycloak** (`dev,keycloak`, по умолчанию): получить токен и передавать
  заголовком `Authorization: Bearer <token>`:

  ```bash
  TOKEN=$(curl -s -X POST \
    http://localhost:8180/realms/realhelpdesk/protocol/openid-connect/token \
    -d grant_type=password -d client_id=realhelpdesk-api \
    -d username=user@example.com -d password=user-pass | jq -r .access_token)
  curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:8443/api/v1/users/profile
  ```

- **Внутренний JWT** (после `make profile-local`): зарегистрировать
  пользователя `POST /api/v1/auth/register` и подтвердить email — письмо
  с кодом придёт в smtp4dev; cookies дальше держит браузер или Swagger UI.
  Пока email не подтверждён, создание портала отвечает `403`.

Переключение контуров: `make profile-local` / `make profile-keycloak` —
меняют `SPRING_PROFILES_ACTIVE` в `.env` и пересоздают контейнер `app`.

## Dev: приложение на хосте (IDEA/CLI)

Инфраструктура — в Docker, приложение — на хосте с отладчиком:

```bash
make up-deps   # только БД, MinIO, smtp4dev, Keycloak
```

В IntelliJ IDEA (2025.2+) конфигурация `RealHelpDeskApplication`
(`.run/RealHelpDeskApplication.run.xml`) уже настроена: профили `dev,host`,
`infrastructure/.env` и инлайн-переменные. Кнопка Debug — приложение
стартует за 2–3 секунды против `127.0.0.1:5432`/`9000`/`25`. Альтернатива
без IDEA: `make run-local` (профили `dev,host` из CLI).

## Работа с данными и конфигом

```bash
make apply-config   # применить изменённые конфиги: sync .env + пересоздать app без сборки
make restart        # перезапустить только приложение
make logs           # логи приложения (follow)
make ps             # статус контейнеров
make db-seed        # локальные фикстуры: демо-пользователи, портал, заявка
make migrate        # миграции Liquibase на основную БД
make down           # остановить стек (тома с данными сохраняются)
make down-volumes   # остановить и удалить тома (данные БД/MinIO)
make backup         # pg_dump БД и MinIO в ./backups/
make restore BACKUP_FILE=backups/db_....dump   # восстановление из дампа
```

## Тесты и проверки

```bash
make test                       # юнит-тесты (стек не нужен)
make verify                     # + интеграционные *IT (стек не нужен)
make format / make check        # форматирование и проверки стиля
make e2e-all                    # все e2e-сценарии (нужен поднятый стек)
make e2e-jwt                    # один сценарий (список — docs/testing.md)
make dev-test                   # mvn verify + e2e подряд
make check-ci                   # CI-проверки локально: gitleaks + hadolint + trivy
```

Подробности, список e2e-сценариев и нагрузочное тестирование (k6) —
в [`testing.md`](testing.md).

## Наблюдаемость

```bash
make obs-up      # стек + Mimir (:9090), Grafana (:3001), Tempo, Loki, Collector
make obs-down    # остановить наблюдаемость (тома целы)
make obs-open    # открыть Grafana в браузере
make obs-reload  # перечитать правила алертов Mimir
make obs-alerts  # текущие состояния алертов
```

Метрики — на management-порту 8081 (`/actuator/prometheus`, HTTP Basic).
Архитектура сигналов, правила кардинальности и отладка —
в [`observability/overview.md`](observability/overview.md).

## Нагрузочное тестирование

```bash
make load         # user journey под нагрузкой (10 VU, 5 минут)
make load-smoke   # короткий smoke (2 VU, 1 минута)
```

Устройство пула пользователей, пороги k6 и параметры — в
[`testing.md`](testing.md#нагрузочное-тестирование-k6).

## Prod: рабочий контур

Рабочий контур отличается от dev: без dev-сервисов (smtp4dev, Keycloak,
публикуемые порты БД), образ тянется из ghcr.io, конфигурация — секция
«Рабочий контур» в `infrastructure/.env`:

```bash
make up-prod   # pull образа и запуск (профили infra+app, docker-compose.prod.yaml)
```

Требования к конфигурации жёсткие: `ProdPreflightValidator` не даст
стартовать с отладочными значениями (выключенная капча, localhost в CORS,
самоподписанный keystore, почта на локальном SMTP). Профиль — `prod`
(при внешнем Keycloak — `prod,keycloak`), адреса Keycloak задаются в `.env`
(`KC_JWK_SET_URI`, `KC_ISSUER_URI`, `KC_HOSTNAME`).

Образ для реестра: `make docker-build` (метка GIT_SHA), публичный контур
CI/CD — `.github/workflows/deploy.yml`. Резервные копии — `make backup` /
`make restore`, реагирование на инциденты — [`runbooks/`](runbooks/).

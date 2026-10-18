# Флоу сборки и запуска RealHelpDesk

Практический путеводитель: что запускать, чем отличаются сценарии, где какие
порты и креды. Подробный разбор конфигурации — в `README.md`.

## 1. Dev-среда (локальная разработка)

### 1.1 Первый запуск

```bash
# 1. Создать infrastructure/.env из шаблона (недостающие ключи дописываются,
#    пустые секреты генерируются openssl rand -base64 32; свои значения не затираются)
make env

# 2. Поднять полный стек (профили infra + local-tools + app, сборка образа, в фоне)
make up
```

После старта:

| Сервис | Адрес | Креды/заметки |
|---|---|---|
| API | `https://localhost:8443` | самоподписанный keystore генерируется при старте app |
| Swagger | `https://localhost:8443/swagger-ui.html` | |
| Actuator health | `http://localhost:8081/actuator/health` | управляющий порт, HTTP |
| Prometheus-скрейп | `http://localhost:8081/actuator/prometheus` | HTTP Basic: `METRICS_USER`/`METRICS_PASSWORD` |
| PostgreSQL | `127.0.0.1:5432` | `user`/`desk` (override публикует порт только на loopback) |
| MinIO API | `http://127.0.0.1:9000` | `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` |
| MinIO Console | `http://127.0.0.1:9001` | те же cred |
| smtp4dev UI | `http://127.0.0.1:3000` | письма dev-стенда; SMTP `127.0.0.1:25` |
| Keycloak | `http://127.0.0.1:8180` | `admin`/`admin` (или `KEYCLOAK_ADMIN*`), realm импортируется из `infrastructure/keycloak/` |
| Grafana | `http://localhost:3001` | только после `make obs-up`; `admin`/`GRAFANA_ADMIN_PASSWORD` |

### 1.2 Compose-файлы и профили

Профиль compose — выбор *какие сервисы* запускать; средовые различия
конфигурации — в отдельных файлах, собираемых цепочкой `-f`:

| Файл | Зачем |
|---|---|
| `infrastructure/docker-compose.yaml` | База: сервисы, профили, env-мост `services.app.environment` (секреты через `${VAR:?}`) |
| `infrastructure/docker-compose.override.yaml` | Локально: порты postgres/minio на `127.0.0.1` + сеть `backend` (для app на хосте). Подключают только локальные цели make |
| `infrastructure/docker-compose.prod.yaml` | Рабочий контур: без портов БД наружу; override физически не подключается |
| `infrastructure/docker-compose.host.yaml` | `include:`-сборка base+override одним файлом — spring-boot-docker-compose принимает только один `-f` |

Порты нельзя переключить профилем (пришлось бы дублировать сервисы), а
отдельный prod-файл гарантирует, что случайный `--profile` не выбросит БД
наружу. Цепочки `-f` инкапсулированы в Makefile (`COMPOSE`, `COMPOSE_PROD`).

Сервисы и их профили:

| Профиль | Сервисы | Кто поднимает |
|---|---|---|
| `infra` | `postgres`, `minio` (внутренняя сеть `data`) | `make up-deps`/`up`/`up-prod` |
| `local-tools` | `smtp` (smtp4dev), `keycloak`, `keycloak-db-init` | `make up-deps`/`up`; **не** в prod |
| `app` | `app` | `make up`/`up-prod` |
| `monitoring` | `mimir`, `grafana`, `tempo`, `loki`, `otel-collector`, `alloy` | `make obs-up` |
| `load` | `k6` | `make up-load`/`load` |

### 1.3 Повседневная разработка

| Сценарий | Команда | Время |
|---|---|---|
| Поднять стек (первый или после `down`) | `make up` | 1–2 мин |
| Поднять только инфраструктуру (БД, MinIO, smtp4dev, Keycloak) | `make up-deps` | секунды |
| Запустить app на хосте против dockerized-инфры | Debug в IDEA (конфиг `RealHelpDeskApplication`) или `make run-local` | секунды |
| Стек в foreground со всеми логами (Ctrl+C — стоп) | `make dev-run` | |
| Перезапустить только app (быстрый цикл) | `make restart` | секунды |
| Применить изменения `.env` к запущенному стеку | `make apply-config` (sync + пересоздать app без сборки) | секунды |
| Логи app / статус контейнеров | `make logs` / `make ps` | |
| Остановить стек | `make down` | секунды |
| Полная очистка (с удалением томов БД/MinIO) | `make down-volumes` | секунды |

### 1.4 Inner loop: правка Java-кода

**Вариант A — в контейнере** (штатный):
```bash
make up          # пересборка слоя app (docker layer cache из m2 ускоряет)
```
Любая правка Java требует пересборки образа.

**Вариант B — на хосте** (быстрый):
```bash
make up-deps    # один раз: поднять infra + local-tools
# Debug в IDEA (конфиг RealHelpDeskApplication, профили dev,host) —
# при отсутствии стека инфру поднимет spring-boot-docker-compose сам;
# либо CLI:
make run-local  # spring-boot:run на хосте (нужен JDK 21+)
```
Правка кода → Hot-reload Spring Boot DevTools (секунды).

Что делает профиль `host` (`application-host.properties`):
- адреса инфры на loopback: `127.0.0.1:5432` (БД), `127.0.0.1:9000` (MinIO),
  `127.0.0.1:25` (SMTP);
- `spring-boot-docker-compose`: при старте сам поднимает/подхватывает
  infra+local-tools (lifecycle `start-only` — стек не останавливается вместе
  с приложением);
- SSL выключен (`SSL_ENABLED=false` инлайном) — HTTP без keystore на хосту.

Оговорки хостового запуска:
- OS-env из `.env` перекрывает профильные файлы: run-конфиги инлайном
  добавляют `MINIO_ENDPOINT=http://127.0.0.1:9000` (в `.env` — контейнерное
  `http://minio:9000`) и дефолты `OTLP_TRACING_ENDPOINT`/`TRUST_PROXY_HEADERS`/
  `CLIENT_IP_FORWARDED_INDEX`;
- контур Keycloak на хосту не включён: при необходимости задайте
  `KC_JWK_SET_URI=http://127.0.0.1:8180/realms/realhelpdesk/protocol/openid-connect/certs`
  инлайном (см. комментарий в `application-host.properties`).

### 1.5 Тесты

```bash
make test        # юнит-тесты (surefire; H2 для слоя репозиториев, быстро)
make verify      # юнит + интеграционные *IT (failsafe; Testcontainers PostgreSQL 18)
make check       # spotless:check + checkstyle:check
make format      # spotless:apply
make dev-test    # verify + все e2e-сценарии (нужен поднятый стек)
```

Отдельно: `ApplicationConfigSchemaTest` сверяет парность ключей между
`application*.properties` (`${VAR}` без дефолтов), `services.app.environment`
compose и `.env.example` — сломали схему → упал `make test`.

### 1.6 E2E-тесты

Шелл-сценарии в `scripts/e2e-*.sh`, ходят в реальный стек по
`https://localhost:8443` (нужен `make up`). Каждый сценарий автоматически
переводит приложение на нужный профиль аутентификации и сбрасывает
in-memory рейт-лимиты (`POST /api/v1/dev/ratelimit-reset`, эндпоинт доступен
только в профиле `dev`).

| Команда | Сценарий |
|---|---|
| `make e2e-portal-access` | права на портал (роли operator/user) |
| `make e2e-ratelimit` | отклонения по rate-limit |
| `make e2e-jwt` | полный JWT-поток: register → login → refresh → logout |
| `make e2e-notifications` | in-app оповещения (long polling) |
| `make e2e-portal-transfer` | передача владения порталом |
| `make e2e-token-cleanup` | очистка отозванных/протухших refresh-токенов (пересоздаёт app) |
| `make e2e-keycloak` | вход через Keycloak (профиль `dev,keycloak`; сервис keycloak из `local-tools`) |
| `make e2e-all` | все сценарии подряд (лимиты сбрасываются перед каждым) |

### 1.7 Профили аутентификации

```bash
make profile-local    # внутренний JWT (cookie, /api/v1/auth/*) — по умолчанию
make profile-keycloak # Keycloak (openid-connect)
```

Обе цели правят `SPRING_PROFILES_ACTIVE` в `infrastructure/.env`
(скрипт `scripts/docker/set-profile.sh`) и пересоздают контейнер `app`.
Контур Keycloak: токены выпускает сервис `keycloak`, приложение проверяет
подпись по JWK и читает email/realm-роли; клиент — `realhelpdesk-api`,
realm импортируется из `infrastructure/keycloak/` при первом старте.

### 1.8 Наблюдаемость

```bash
make obs-up      # стек + monitoring: Grafana :3001, Mimir :9090,
                 # Tempo :3200, Loki :3100; OTLP — http://otel-collector:4318/v1/traces
make obs-open    # открыть Grafana
make obs-alerts  # текущие состояния алертов Mimir
make obs-reload  # перечитать правила алертов (mimir/rules/)
make obs-down    # остановить стек (томы целы)
```

Сбор логов/метрик: `alloy` → Loki (логи) и Mimir (метрики со скрейпа
`app:8081/actuator/prometheus`); трейсы: app → otel-collector → Tempo.
Подробности — `docs/observability/overview.md`.

### 1.9 Миграции и сиды (Liquibase с хоста)

Миграции применяет само приложение при старте (`DB_MIGRATIONS_ENABLED`).
Цели ниже — для чистого/ручного прогона; нужен поднятый postgres
(`make up-deps`, порт `127.0.0.1:5432`):

```bash
make migrate           # контекст schema (чистая схема; context=seed НЕ применяется)
make db-seed           # schema + seed: демо-фикстуры, идемпотентны
make down-volumes && make up   # чистая dev-база заново
```

### 1.10 Нагрузочное тестирование (k6)

```bash
make load-smoke   # smoke: 2 VU, 1 минута
make load         # user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
```

`up-load` (общая зависимость) переводит приложение на внутренний JWT-контур
(пул сидируется через `/api/v1/auth/register`) и поднимает стек с
`TRUST_PROXY_HEADERS=true` — только локально. Скрипты и сценарии —
`scripts/load/`.

### 1.11 OpenAPI для фронтенда

```bash
make openapi        # вытащить спеку с запущенного стека → openapi.json
make openapi-types  # TypeScript-типы → web/src/api/schema.d.ts (нужен npx)
```

---

## 2. Prod-среда (VPS)

### 2.1 Архитектура

```
[Клиент] → Nginx (VPS, TLS/LE) → 127.0.0.1:8080 (app container, loopback)
```

- TLS завершает Nginx на хосте (Certbot/Let's Encrypt).
- App биндится **только на loopback** — наружу недоступен; порты БД/MinIO
  на хост не публикуются (override не подключён).
- `TRUST_PROXY_HEADERS=true` — Spring читает `X-Forwarded-For`.
- Сборка на сервере **запрещена** — только `pull` из ghcr.io
  (`up-prod` с `--no-build`, образ — `ghcr.io/dx9119/realhelpdesk-api:${IMAGE_TAG}`).
- Все различия prod-контура (пароли SMTP, домены, профиль `prod` или
  `prod,keycloak`) — в секции «Рабочий контур» файла `infrastructure/.env`
  на сервере.

### 2.2 Ручной деплой

```bash
# На сервере:
cd /opt/realhelpdesk

# 1. Синхронизировать IMAGE_TAG в .env
# (обычно пишет CI/CD, но можно вручную)
echo "IMAGE_TAG=sha-abc123" >> infrastructure/.env

# 2. Pull нового образа
docker compose -f infrastructure/docker-compose.yaml \
  -f infrastructure/docker-compose.prod.yaml \
  --profile infra --profile app pull app

# 3. Поднять prod-стек
make up-prod

# 4. Проверить health
curl -sf http://127.0.0.1:8081/actuator/health
```

### 2.3 Автоматический деплой (CI/CD)

```
git tag v1.2.3
  └─► GitHub Actions (deploy.yml)
       └─► SSH на VPS (concurrency: deploy-production)
            └─► Записать IMAGE_TAG в .env (sed, предыдущий тег запоминается)
            └─► docker compose pull app
            └─► make up-prod
            └─► Health-gate (до 90 сек, 18×5 сек /actuator/health)
                 ├─► OK → деплой завершён
                 └─► FAIL → откат: IMAGE_TAG на предыдущий + up-prod
```

**Триггеры:**
- Пуш тега `v*` — автоматически (`IMAGE_TAG=sha-<git_sha>`)
- `workflow_dispatch` — ручной запуск с полем `image_tag`

Секреты environment `production`: `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`,
`VPS_PORT`, `VPS_APP_DIR` (дефолт `/opt/realhelpdesk`), `GHCR_TOKEN`.

### 2.4 Prod-preflight

При запуске с `SPRING_PROFILES_ACTIVE=prod` выполняется `ProdPreflightValidator`:
- капча включена;
- CORS настроен строго (без localhost-допусков);
- keystore самоподписанный отвергается;
- SMTP доступен;
- миграции применены;
- `ddl-auto=validate` не может быть выключен env'ом.

Старт с debug-конфигурацией валится — рабочий контур не поднимется.

### 2.5 Бэкапы

```bash
make backup                                        # pg_dump -Fc + mc mirror бакета → ./backups/
make restore BACKUP_FILE=backups/db_2026-10-18_03-00.dump
# restore: stop app → pg_restore --clean --if-exists → up app
```

Ротация — удаление артефактов старше 14 дней (делает `backup.sh`).
На сервере рекомендуется crontab:
```
0 3 * * * cd /opt/realhelpdesk && make backup
```

---

## 3. CI/CD (GitHub Actions)

### 3.1 CI (на push в main / PR)

```
job: build — Maven (JDK 21)
  └─► spotless:check + checkstyle:check + verify (юнит + Testcontainers IT)

job: docker
  └─► hadolint → сборка образа → trivy (CRITICAL/HIGH) → gitleaks
  └─► при push в main: login ghcr.io + push :sha-<git_sha> и :latest
      (на PR пуш образа не выполняется)

job: e2e — compose-стек (JDK 21)
  └─► make env → make up → wait health (app) → wait Keycloak
  └─► make e2e-all
  └─► при провале — ps + logs всех контейнеров в CI-вывод
  └─► always: make down
```

### 3.2 Deploy (по тегу)

```
tag v* / workflow_dispatch
  └─► job: deploy (environment: production)
       └─► SSH на VPS
            └─► pull + up-prod + health-gate + авто-откат (см. §2.3)
```

---

## 4. Переменные окружения

Единственный env-файл — `infrastructure/.env` (шаблон `infrastructure/.env.example`).
Он работает в двух ролях: `env_file` контейнеров и источник `${VAR}`-
интерполяции compose. Переименований нет: приложение читает те же имена,
что и compose (`DB_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`).

- **Контейнер app**: `env_file: ./.env` в `infrastructure/docker-compose.yaml`;
  секреты продублированы в `services.app.environment` как `${VAR:?}` —
  пустое значение валит запуск сразу и понятной ошибкой.
- **Хост (IDEA/CLI)**: EnvFile (`.env` в run-конфиге IDEA
  `.run/RealHelpDeskApplication.run.xml`) / построчная загрузка `.env` в
  `scripts/dev/run-local.sh` + профиль Spring `host`. Run-конфиги инлайном
  добавляют `SSL_ENABLED=false`, `MINIO_ENDPOINT=http://127.0.0.1:9000` и
  дефолты `OTLP_TRACING_ENDPOINT`/`TRUST_PROXY_HEADERS`/
  `CLIENT_IP_FORWARDED_INDEX` — без них схема `${VAR}` не резолвится на хосте
  (OS-env перекрывает профильные файлы, поэтому задавать их надо инлайном).
- **Схему ключей** и парность `.env.example`/compose сверяет
  `ApplicationConfigSchemaTest` (`make test`). Ключи `KC_*`/`KEYCLOAK_*` в
  схему не входят — их читают `application-keycloak.properties` и сервис keycloak.

Правила `.env` (детально — в комментариях `infrastructure/.env.example`):
`$$` вместо `$` в значениях, `#` внутри значения не работает, `make env`
дописывает недостающие ключи, не перезаписывая ваши значения.

---

## 5. Полезные команды

```bash
# Навигация
make help              # все команды с описаниями

# Сборка/тесты (Maven)
make build             # jar без тестов
make package           # полная сборка с тестами
make test | verify     # юнит | юнит + IT
make check | format    # стиль

# Стек
make up | up-deps | up-prod | down | down-volumes
make dev-run           # foreground со логами
make restart | logs | ps | shell
make env | apply-config

# Данные
make migrate | db-seed
make backup | restore BACKUP_FILE=...

# Контур аутентификации
make profile-local | profile-keycloak

# Наблюдаемость
make obs-up | obs-open | obs-alerts | obs-reload | obs-down

# E2E / нагрузка / OpenAPI
make e2e-all | e2e-jwt | e2e-keycloak | ...   # см. §1.6
make load | load-smoke
make openapi | openapi-types

# Образ
make docker-build | check-image
```

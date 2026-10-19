# Запуск проекта

Практический путеводитель: что запускать, чем отличаются сценарии, где какие
порты и креды. Подробный разбор конфигурации — в
[`configuration.md`](configuration.md).

## 1. Подготовка (однократно)

```bash
make env
```

Создаёт `infrastructure/.env` из шаблона и генерирует пустые секреты
случайными значениями. Существующий `.env` скрипт не меняет.

Заполнить секреты в `infrastructure/.env` — в шаблоне это пустые строки,
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

---

## 2. Dev: полный стек в контейнерах

### 2.1 Первый запуск

```bash
make up        # сборка образа и запуск всего в фоне
make dev-run   # то же в foreground: логи всех сервисов, Ctrl+C — остановка
```

После старта:

| Сервис | Адрес | Креды / заметки |
|---|---|---|
| API | `https://localhost:8443` | самоподписанный keystore генерируется при старте app |
| Swagger | `https://localhost:8443/swagger-ui.html` | |
| Actuator health | `http://localhost:8081/actuator/health` | управляющий порт, HTTP |
| Prometheus-скрейп | `http://localhost:8081/actuator/prometheus` | HTTP Basic: `METRICS_USER`/`METRICS_PASSWORD` |
| PostgreSQL | `127.0.0.1:5432` | `user`/`desk` (override публикует порт только на loopback) |
| MinIO API | `http://127.0.0.1:9000` | `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` |
| MinIO Console | `http://127.0.0.1:9001` | те же cred |
| smtp4dev UI | `http://127.0.0.1:3000` | письма dev-стенда; SMTP `127.0.0.1:25` |
| Keycloak | `http://127.0.0.1:8180` | `admin`/`admin` (или `KEYCLOAK_ADMIN*`), realm из `infrastructure/keycloak/` |

Первое обращение к API зависит от контура аутентификации:

- **Keycloak** (`dev,keycloak`, по умолчанию): получить токен и передать
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
передают `SPRING_PROFILES_ACTIVE` инлайном и пересоздают только контейнер
`app` (файлы не меняются; подробности — в
[`configuration.md`](configuration.md#профили-spring)).

### 2.2 Повседневная разработка

| Сценарий | Команда |
|---|---|
| Поднять стек (первый или после `down`) | `make up` |
| Поднять только инфраструктуру (БД, MinIO, smtp4dev, Keycloak) | `make up-deps` |
| Стек в foreground со всеми логами | `make dev-run` |
| Перезапустить только app (быстрый цикл) | `make restart` |
| Применить изменения `.env` к запущенному стеку | `make apply-config` |
| Логи app / статус контейнеров | `make logs` / `make ps` |
| Shell внутри контейнера app (осмотреть ФС, keystore, дампы) | `make shell` |
| Остановить стек | `make down` |
| Полная очистка (с удалением томов) | `make down-volumes` |

---

## 3. Dev: приложение на хосте (IDEA / CLI)

Инфраструктура — в Docker, приложение — на хосте с отладчиком:

```bash
make up-deps   # один раз: поднять infra + local-tools
```

В IntelliJ IDEA (2025.2+) конфигурация `RealHelpDeskApplication`
(`.run/RealHelpDeskApplication.run.xml`) уже настроена: профили `dev,host`,
`infrastructure/.env` и инлайн-переменные. Кнопка Debug — приложение
стартует за 2–3 секунды против `127.0.0.1:5432`/`9000`/`25`. Альтернатива
без IDEA: `make run-local`.

### 3.1 Inner loop: правка Java-кода

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
make run-local  # spring-boot:run на хосте (нужен JDK 25+)
```

Правка кода → перезапуск процесса (секунды, без пересборки образа); в Debug
JVM hot-swap меняет тела методов без рестарта.

Профиль `host` (`application-host.properties`) переводит адреса на loopback
(`127.0.0.1:5432`, `9000`, `25`), выключает SSL и поднимает infra через
spring-boot-docker-compose. OS-env из `.env` перекрывает профильные файлы:
run-конфиги инлайном добавляют `MINIO_ENDPOINT=http://127.0.0.1:9000` и
дефолты `OTLP_TRACING_ENDPOINT`/`TRUST_PROXY_HEADERS`/`CLIENT_IP_FORWARDED_INDEX`.

На хосту контур Keycloak не включён: при необходимости задайте
`KC_JWK_SET_URI=http://127.0.0.1:8180/realms/realhelpdesk/protocol/openid-connect/certs`
инлайном.

---

## 4. Тесты и проверки

```bash
make test        # юнит-тесты (surefire; H2 для слоя репозиториев, быстро)
make verify      # юнит + интеграционные *IT (failsafe; Testcontainers PostgreSQL 18)
make check       # spotless:check + checkstyle:check
make format      # spotless:apply
make dev-test    # verify + все e2e-сценарии (нужен поднятый стек)
make check-ci    # CI-проверки локально: gitleaks + hadolint + trivy (полный прогон)
```

Сборка артефактов, если нужен jar без запуска стека:

```bash
make compile     # компиляция main и test
make build       # jar без тестов (package -DskipTests)
make package     # полная сборка: тесты + jar
make clean       # очистить target/
make run         # spring-boot:run против локальных настроек (без dockerized-инфры)
```

Отдельно: `ApplicationConfigSchemaTest` сверяет парность ключей между
`application*.properties` (`${VAR}` без дефолтов), `services.app.environment`
compose и `.env.example` — сломали схему → упал `make test`.

### Проверки образа

Те же шаги, что выполняет job `docker` в CI, по отдельности — чтобы
поймать проблему до полного `check-ci` (он гоняет их подряд и самый
долгий шаг выполняет последним):

```bash
make docker-build      # собрать образ приложения с меткой GIT_SHA
make check-image       # образ собран и пригоден: секреты, ФС, содержимое jar
make check-secrets     # gitleaks: секреты в дереве и истории коммитов
make check-dockerfile  # hadolint: линтер Dockerfile
make ci-image          # собрать образ realhelpdesk:ci как в CI (нужен trivy)
make check-image-vuln  # trivy: CRITICAL/HIGH с фиксом; таймаут 15m — разбор
                       # вложенных jar в fat-jar дольше дефолтных 5 минут
```

### E2E-тесты

Шелл-сценарии в `scripts/e2e-*.sh`, ходят в реальный стек по
`https://localhost:8443` (нужен `make up`). Каждый сценарий автоматически
переводит приложение на нужный профиль аутентификации и сбрасывает
in-memory рейт-лимиты.

| Команда | Сценарий |
|---|---|
| `make e2e-portal-access` | права на портал (роли operator/user) |
| `make e2e-ratelimit` | отклонения по rate-limit |
| `make e2e-jwt` | полный JWT-поток: register → login → refresh → logout |
| `make e2e-notifications` | in-app оповещения (long polling) |
| `make e2e-portal-transfer` | передача владения порталом |
| `make e2e-token-cleanup` | очистка отозванных/протухших refresh-токенов |
| `make e2e-keycloak` | вход через Keycloak (профиль `dev,keycloak`) |
| `make e2e-all` | все сценарии подряд (лимиты сбрасываются перед каждым) |
| `make e2e-reset-limits` | сбросить in-memory рейт-лимиты вручную (dev-эндпоинт) |

Подробности, список сценариев и нагрузочное тестирование (k6) —
в [`testing.md`](testing.md).

---

## 5. Наблюдаемость

```bash
make obs-up      # стек + monitoring: Mimir :9090, Tempo :3200, Loki :3100
                 # (Collector и Alloy — без портов на хост, только сеть compose)
make obs-alerts  # текущие состояния алертов Mimir
make obs-reload  # перечитать правила алертов (mimir/rules/)
make obs-down    # остановить стек (томы целы)
```

Сбор логов/метрик: `alloy` → Loki (логи) и Mimir (метрики со скрейпа
`app:8081/actuator/prometheus`); трейсы: app → otel-collector → Tempo.
Архитектура сигналов, правила кардинальности и отладка —
в [`observability/overview.md`](observability/overview.md).

---

## 6. Нагрузочное тестирование (k6)

```bash
make load-smoke   # smoke: 2 VU, 1 минута
make load         # user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
make up-load      # только стек для нагрузки, без прогона k6
```

`up-load` переводит приложение на внутренний JWT-контур (пул сидируется
через `/api/v1/auth/register`) и поднимает стек с
`TRUST_PROXY_HEADERS=true` — только локально. Скрипты и сценарии —
`scripts/load/`. Устройство пула пользователей, пороги k6 и параметры —
в [`testing.md`](testing.md#нагрузочное-тестирование-k6).

---

## 7. Миграции и сиды (Liquibase с хоста)

Миграции применяет само приложение при старте (`DB_MIGRATIONS_ENABLED`).
Цели ниже — для чистого/ручного прогона; нужен поднятый postgres
(`make up-deps`, порт `127.0.0.1:5432`):

```bash
make migrate           # контекст schema (чистая схема; context=seed НЕ применяется)
make db-seed           # schema + seed: демо-фикстуры, идемпотентны
make down-volumes && make up   # чистая dev-база заново
```

---

## 8. OpenAPI для фронтенда

```bash
make openapi        # вытащить спеку с запущенного стека → openapi.json
make openapi-types  # TypeScript-типы → web/src/api/schema.d.ts (нужен npx)
```

---

## 9. Prod: рабочий контур

### 9.1 Архитектура

```
[Клиент] → Nginx (VPS, TLS/LE) → 127.0.0.1:8080 (app container, loopback)
```

- TLS завершает Nginx на хосте (Certbot/Let's Encrypt).
- App биндится **только на loopback** — наружу недоступен; порты БД/MinIO
  на хост не публикуются (override не подключён).
- `TRUST_PROXY_HEADERS=true` — Spring читает `X-Forwarded-For`.
- Сборка на сервере **запрещена** — только `pull` из ghcr.io
  (`up-prod` с `--no-build`, образ — `ghcr.io/dx9119/realhelpdesk-api:${IMAGE_TAG}`).
- Все различия prod-контура (пароли SMTP, домены, адреса внешнего Keycloak) —
  в секции «Рабочий контур» файла `infrastructure/.env` на сервере.
  Профиль `prod` подставляет сам `docker-compose.prod.yaml`
  (`${SPRING_PROFILES_ACTIVE:-prod}`): забытый на сервере ключ не поднимет
  dev-режим. Для внешнего Keycloak задайте `SPRING_PROFILES_ACTIVE=prod,keycloak`
  в `.env` сервера.

### 9.2 Ручной деплой

```bash
# На сервере:
cd /opt/realhelpdesk

# 1. Синхронизировать IMAGE_TAG в .env
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

### 9.3 Автоматический деплой (CI/CD)

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

### 9.4 Prod-preflight

При запуске с `SPRING_PROFILES_ACTIVE=prod` выполняется
`ProdPreflightValidator`:

- капча включена;
- CORS настроен строго (без localhost-допусков);
- keystore самоподписанный отвергается;
- SMTP доступен;
- миграции применены;
- `ddl-auto=validate` не может быть выключен env'ом.

Старт с debug-конфигурацией валится — рабочий контур не поднимется.

### 9.5 Бэкапы

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

## 10. CI/CD (GitHub Actions)

### 10.1 CI (на push в main / PR)

```
job: build — Maven (JDK 25)
  └─► spotless:check + checkstyle:check + verify (юнит + Testcontainers IT)

job: docker
  └─► hadolint → сборка образа → trivy (CRITICAL/HIGH) → gitleaks
  └─► при push в main: login ghcr.io + push :sha-<git_sha> и :latest
      (на PR пуш образа не выполняется)

job: e2e — compose-стек (JDK 25)
  └─► make env → make up → wait health (app) → wait Keycloak
  └─► make e2e-all
  └─► при провале — ps + logs всех контейнеров в CI-вывод
  └─► always: make down
```

### 10.2 Deploy (по тегу)

```
tag v* / workflow_dispatch
  └─► job: deploy (environment: production)
       └─► SSH на VPS
            └─► pull + up-prod + health-gate + авто-откат (§9.3)
```

---

## 11. Вспомогательные скрипты

Каждый скрипт вызывается из цели Makefile — руками запускать их не нужно,
разве что для отладки самого скрипта. Каталог `scripts/` не содержит
«мёртвых» файлов: всё, что в нём лежит, задействовано в Makefile, Dockerfile
или compose.

### `scripts/docker/` — жизненный цикл образа и контейнера

| Скрипт | Назначение | Вызывается из |
|---|---|---|
| `entrypoint.sh` | Точка входа контейнера: генерирует keystore при старте (если не смонтирован извне) и запускает JVM. Пароль не попадает в слои образа и `docker history`, смена пароля не требует пересборки | `Dockerfile` (`ENTRYPOINT`) |
| `init-secrets.sh` | Создаёт `infrastructure/.env` из шаблона (если файла нет) и заполняет пустые секреты случайными значениями. Существующий `.env` не меняет — только предупреждает о ключах, которых в нём нет | `make env` |
| `check-image.sh` | Гигиена собранного образа: нет ли секретов в слоях, лишней ФС на пути к jar, цел ли сам jar | `make check-image` |
| `backup.sh` | `pg_dump -Fc` + `mc mirror` бакета MinIO в `./backups/`, ротация старше `BACKUP_RETENTION_DAYS` (14) | `make backup` |

### `scripts/dev/` — запуск на хосте

| Скрипт | Назначение | Вызывается из |
|---|---|---|
| `run-local.sh` | CLI-эквивалент run-конфига IDEA (§3): подгружает `.env`, выключает SSL, переводит адреса на loopback и запускает `spring-boot:run` против dockerized-инфры | `make run-local` |

### `scripts/load/` — нагрузочное тестирование (k6)

| Скрипт | Назначение | Вызывается из |
|---|---|---|
| `run.sh` | Оркестрация прогона: проверяет стек, сидирует пул пользователей через `/auth/register` (с реальной капчей), верифицирует email через `psql`, запускает k6 | `make load`, `make load-smoke` |
| `solve-pow.js` | Решает PoW-задачу капчи (`/captcha/challenge` → `captchaToken`) на Node.js; нужен и при `CAPTCHA_ENABLED=false`, т.к. контракт запроса тот же | `run.sh` |
| `user-journey.js` | Сценарий k6: пользовательский путь (вход → заявка → вложение → оповещения) с проверками порогов | `docker-compose.yaml`, сервис `k6` |

### `scripts/e2e-*.sh` — сквозные сценарии

Шелл-сценарии против реального стека (см. §4). Каждый переводит приложение
на нужный профиль аутентификации и сбрасывает рейт-лимиты через зависимости
Makefile, поэтому запускать их напрямую не следует — профиль не применится.

| Скрипт | Проверяет |
|---|---|
| `e2e-portal-access.sh` | разграничение прав на портал между `operator` и `user` |
| `e2e-jwt-flow.sh` | полный жизненный цикл внутреннего JWT: регистрация, подтверждение email, вход, refresh, выход |
| `e2e-notifications.sh` | доставку in-app оповещений long polling (повторы — только под `E2E_REPEATS=1`) |
| `e2e-portal-transfer.sh` | передачу владения порталом и историю передач |
| `e2e-token-cleanup.sh` | фоновое расписание очистки refresh-токенов: занижает сроки JWT и cron инлайном, пересоздаёт `app`, по завершении возвращает штатный конфиг (`infrastructure/.env` не меняется) |
| `e2e-keycloak.sh` | вход через Keycloak: realm, обмен кода на токены, работу профиля `dev,keycloak` |
| `e2e-ratelimit.sh` | бакеты рейт-лимитов, `429` и `Retry-After`; в `e2e-all` не входит — логика покрыта `RateLimitServiceTest` |


# Запуск проекта

Практический путеводитель: что запускать, чем отличаются сценарии, где какие
порты и креды. Подробный разбор конфигурации — в
[`configuration.md`](configuration.md).

## Быстрый старт

```bash
make env && make dev-run && make run-local
```

`make env` создаёт `infrastructure/.env` с секретами, `make dev-run` поднимает
инфраструктуру и наблюдаемость (приложение в контейнер не собирается),
`make run-local` запускает приложение на хосте; токены выпускает Keycloak.
Адреса
поднятых сервисов (API, Swagger, Actuator, smtp4dev, Keycloak) —
[таблица в §2.1](#21-dev-контур-первый-запуск).

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
| `CAPTCHA_HMAC_SECRET` | случайная строка от 32 символов |
| `DB_PASSWORD` | любой пароль PostgreSQL |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | учётная запись MinIO |
| `METRICS_PASSWORD` | пароль сервисной учётки скрейпа метрик |
| `KEYCLOAK_DB_PASSWORD` | пароль отдельного кластера PostgreSQL для Keycloak |

`MAIL_PASSWORD` нужен только для внешнего SMTP, `KEYCLOAK_ADMIN_PASSWORD` —
только если стенд Keycloak доступен не только вам.

`make env` генерирует все перечисленные значения автоматически — руками их
заполнять не нужно, если стенд локальный.

---

## 2. Dev: запуск проекта

Штатный путь: приложение на хосте против dockerized-инфраструктуры.

| Вариант | Команды | Когда выбирать |
|---|---|---|
| Приложение на хосте | `make dev-run` (в фоне, полный стек) + Debug в IDEA / `make run-local` | частая правка Java: рестарт за секунды, JVM hot-swap, без пересборки образа |

### 2.1 Dev-контур: первый запуск

```bash
make dev-run                # инфраструктура + наблюдаемость в фоне: БД, MinIO, smtp4dev, Keycloak, Mimir/Tempo/OTel
make run-local              # приложение на хосте (dev,host); токены выпускает Keycloak
```

После старта:

| Сервис | Адрес | Креды / заметки |
|---|---|---|
| API | `http://localhost:8080` | |
| Swagger | `http://localhost:8080/swagger-ui.html` | |
| Actuator health | `http://localhost:8081/actuator/health` | управляющий порт, HTTP |
| Prometheus-скрейп | `http://localhost:8081/actuator/prometheus` | HTTP Basic: `METRICS_USER`/`METRICS_PASSWORD` |
| PostgreSQL | `127.0.0.1:5432` | `user`/`desk` (override публикует порт только на loopback) |
| MinIO API | `http://127.0.0.1:9000` | `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` |
| MinIO Console | `http://127.0.0.1:9001` | те же cred |
| smtp4dev UI | `http://127.0.0.1:3000` | письма dev-стенда; SMTP `127.0.0.1:25` |
| Keycloak | `http://127.0.0.1:8180` | `admin`/`admin` (или `KEYCLOAK_ADMIN*`), realm из `infrastructure/keycloak/` |

Первое обращение к API: получить токен в Keycloak (direct access grant) и
передать его заголовком `Authorization: Bearer <token>`:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/realhelpdesk/protocol/openid-connect/token \
  -d grant_type=password -d client_id=realhelpdesk-api \
  -d username=user@example.com -d password=user-pass | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/profile
```

Учётная запись заводится в БД при первом запросе с валидным токеном
(`KeycloakAuthenticationConverter`). Пока email не подтверждён в Keycloak,
создание портала отвечает `403`.

Профиль приложения один — `dev,host`; переключения контура нет. Подробности —
в [`configuration.md`](configuration.md#профили-spring).

### 2.2 Повседневные команды

| Сценарий | Команда |
|---|---|
| Поднять инфраструктуру и наблюдаемость в фоне (БД, MinIO, smtp4dev, Keycloak, Mimir/Tempo/OTel) | `make dev-run` |
| Запустить приложение на хосте (`dev,host`) | `make run-local` |
| Остановить dev-контур | `make dev-down` |
| Полная очистка (с удалением томов) | `make dev-down--del-volumes` |

### 2.3 Приложение на хосте (IDEA / CLI)

Инфраструктура — в Docker, приложение — на хосте с отладчиком:

```bash
make dev-run       # инфраструктура + наблюдаемость в фоне
```

Стек остаётся в фоне; порт 8080 свободен для хостового процесса. Наблюдаемость
(`monitoring`) продолжает работать: хостовое приложение шлёт трейсы на
`localhost:4318` (порт Collector'а опубликован на loopback), а Alloy собирает
логи и метрики контейнерных сервисов.

В IntelliJ IDEA (2025.2+) конфигурация `RealHelpDeskApplication`
(`.run/RealHelpDeskApplication.run.xml`) уже настроена: запуск через Maven
(`spring-boot:run`, профили `dev,host`) — фазы validate не обходятся
IDE-сборкой. Файл коммитится в git и хранит настройки запуска IDE и
переменные окружения для локального старта — после клона репозитория
конфигурация готова к работе. Env — снапшот значений из
`infrastructure/.env` с инлайн-оверрайдами, а не ссылка на файл: правки
`.env` запуском из IDEA не подхватываются — обновляйте env-карту.
Кнопка Debug — приложение стартует за несколько секунд против
`127.0.0.1:5432`/`9000`/`25`. Альтернатива без IDEA: `make run-local`.

#### Inner loop: правка Java-кода

**На хосте** (быстрый, штатный dev-путь):

```bash
make dev-run        # инфраструктура + наблюдаемость в фоне (один раз)
# Debug в IDEA (конфиг RealHelpDeskApplication, профили dev,host) —
# при отсутствии стека инфру поднимет spring-boot-docker-compose сам;
# либо CLI:
make run-local  # spring-boot:run на хосте (нужен JDK 25+)
```

Правка кода → перезапуск процесса (секунды, без пересборки образа); в Debug
JVM hot-swap меняет тела методов без рестарта.

Профиль `host` (`application-host.properties`) переводит адреса на loopback
(`127.0.0.1:5432`, `9000`, `25`) и поднимает infra через
spring-boot-docker-compose. OS-env перекрывает профильные файлы: оба
run-конфига инлайном задают `MINIO_ENDPOINT=http://127.0.0.1:9000` и
`OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces`;
`TRUST_PROXY_HEADERS`/`CLIENT_IP_FORWARDED_INDEX` в `make run-local`
экспортируются явно, в IDEA берутся из дефолтов схемы (`false`/`0` в
`application.properties`).

Keycloak работает в любом запуске: `KC_JWK_SET_URI` из `.env` указывает на
`keycloak:8080` (compose-сеть), поэтому при запуске на хосте задайте
`KC_JWK_SET_URI=http://127.0.0.1:8180/realms/realhelpdesk/protocol/openid-connect/certs`
инлайном в env-карте IDEA — `make run-local` подставляет loopback-адрес сам.

---

## 3. CI (GitHub Actions)

Запускается на push в main и PR. Приложение нигде не контейнеризуется: в CI,
как и локально, оно стартует на хосте через `spring-boot:run`, в Docker
поднимается только инфраструктура.

```
job: build — Maven (JDK 25)
  └─► spotless:check + checkstyle:check + verify (юнит + Testcontainers IT)

job: checks — статические проверки
  └─► gitleaks (секреты в дереве и истории коммитов)

job: e2e — инфраструктура в compose, приложение на хосте (JDK 25)
  └─► make dev-run (инфраструктура + наблюдаемость)
  └─► make run-local (приложение на хосте, в фоне)
  └─► wait health (app) → wait Keycloak → make e2e-all
  └─► при провале — ps + logs контейнеров и хостовые логи в CI-вывод
  └─► always: make dev-down
```

---

## 4. Тесты и проверки

```bash
make test             # юнит-тесты (surefire; H2 для слоя репозиториев, быстро)
make verify           # юнит + интеграционные *IT (failsafe; Testcontainers PostgreSQL 18)
make check-code-style # spotless:check + checkstyle:check
make check-ci-all     # CI-проверки локально: gitleaks
```

Форматирование и компиляция — goals Maven, отдельных целей нет
(проверки стиля живут в фазе `validate`, поэтому компиляция без них
невозможна):

```bash
./mvnw spotless:apply            # автоформатирование
./mvnw -q test-compile           # компиляция main и test
make build                       # jar с юнит-тестами (package -DskipITs)
make clean                       # очистить target/
```

E2E-прогон собирается из целей вручную: запустить приложение на хосте
(`make run-local`) → `make e2e-all`.

Отдельно: `ApplicationConfigSchemaTest` сверяет парность ключей между
`application*.properties` (`${VAR}` без дефолтов) и `infrastructure/.env.dev.example` —
сломали схему → упал `make test`.

### E2E-тесты

Шелл-сценарии в `scripts/e2e-*.sh`, ходят в реальный стек по
`http://localhost:8080` (нужно запустить приложение на хосте: `make run-local`).
Контур аутентификации один — Keycloak: токены берутся direct access grant'ом,
дальше запросы идут с `Authorization: Bearer`. E2E-цели отвечают только за
тесты: контейнеры они не поднимают и не пересобирают. Приложение нужно запустить
заранее — `make run-local`; цель `make require-app` проверяет доступность и
подсказывает нужную команду, а `make e2e-reset-limits` сбрасывает in-memory
рейт-лимиты.

| Команда | Сценарий |
|---|---|
| `make e2e-all` | все сценарии: вход, порталы, уведомления |
| `make e2e-keycloak` | агрегатор: вход, порталы, уведомления подряд |
| `make e2e-keycloak-auth` | вход через Keycloak, синхронизация ролей, блокировка |
| `make e2e-portal-access-kc` | порталы через Bearer-токен Keycloak (CRUD) |
| `make e2e-notifications-kc` | уведомления через Bearer-токен Keycloak |
| `make e2e-ratelimit` | отклонения по rate-limit (ручной прогон, вне `e2e-all`) |
| `make e2e-reset-limits` | сбросить in-memory рейт-лимиты вручную (dev-эндпоинт) |

Подробности, список сценариев и нагрузочное тестирование (k6) —
в [`testing.md`](testing.md).

---

## 5. Наблюдаемость

Наблюдаемость поднимается вместе с dev-стеком (`make dev-run`):
Mimir :9090, Tempo :3200, OTLP :4318 — все порты на loopback.

Сбор метрик: `alloy` → Mimir (скрейп
хостового приложения `127.0.0.1:8081/actuator/prometheus`, из контейнера —
`host.docker.internal:8081`); трейсы: app → otel-collector → Tempo.
Архитектура сигналов, правила кардинальности и отладка —
в [`observability/overview.md`](observability/overview.md).

---

## 6. Нагрузочное тестирование (k6)

```bash
make load-smoke  # smoke: 2 VU, 1 минута
make load-full   # user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
```

Обе цели сначала поднимают load-стек (служебная цель `.load-up`), затем гоняют
k6. Пул пользователей сидируется через Keycloak Admin API (master realm,
`admin-cli`), прогон идёт с `TRUST_PROXY_HEADERS=true` — только локально.
Скрипты и сценарии — `scripts/load/`. Устройство пула пользователей, пороги k6
и параметры — в [`testing.md`](testing.md#нагрузочное-тестирование-k6).

---

## 7. Миграции и сиды (Liquibase с хоста)

Миграции применяет само приложение при старте (`DB_MIGRATIONS_ENABLED`),
отдельной цели для них нет. Контекст — `DB_MIGRATIONS_CONTEXTS` (дефолт
`schema`; `schema,seed` добавляет демо-фикстуры). Ручной прогон без контейнера
приложения — goal Maven против `127.0.0.1:5432`, который публикует
`docker-compose.override.yaml`:

```bash
set -a && . infrastructure/.env && set +a
./mvnw -q liquibase:update \
  -Dliquibase.url=jdbc:postgresql://127.0.0.1:5432/desk \
  -Dliquibase.username=user -Dliquibase.password="$DB_PASSWORD" \
  -Dliquibase.contexts=schema

make dev-down--del-volumes   # чистая dev-база: тома БД/MinIO удаляются
```

---

## 8. OpenAPI для фронтенда

```bash
make dev-openapi        # вытащить спеку с запущенного стека → openapi.json
make dev-openapi-types  # TypeScript-типы → web/src/api/schema.d.ts (нужен npx)
```

---

## 9. Вспомогательные скрипты

Каждый скрипт вызывается из цели Makefile — руками запускать их не нужно,
разве что для отладки самого скрипта. Каталог `scripts/` не содержит
«мёртвых» файлов: всё, что в нём лежит, задействовано в Makefile, CI
или compose.

### `scripts/dev/` — запуск на хосте

| Скрипт | Назначение | Вызывается из |
|---|---|---|
| `run-local.sh` | CLI-эквивалент run-конфига IDEA (§2.3): подгружает `.env`, переводит адреса на loopback и запускает `spring-boot:run` против dockerized-инфры | `make run-local` |

### `scripts/load/` — нагрузочное тестирование (k6)

| Скрипт | Назначение | Вызывается из |
|---|---|---|
| `run.sh` | Оркестрация прогона: проверяет стек, сидирует пул пользователей через Keycloak Admin API (создание пользователей, роль `ROLE_USER`), запускает k6 | `make load-full`, `make load-smoke` |
| `user-journey.js` | Сценарий k6: пользовательский путь (вход → заявка → вложение → оповещения) с проверками порогов; токены — direct access grant Keycloak | `docker-compose.load.yaml`, сервис `k6` |

### `scripts/e2e-*.sh` — сквозные сценарии

Шелл-сценарии против реального стека (см. §4). Цели Makefile сначала
проверяют доступность приложения (`make require-app`) и сбрасывают in-memory
рейт-лимиты, поэтому запускать их напрямую не следует — проверка готовности
не выполнится.

| Скрипт | Проверяет |
|---|---|
| `e2e-keycloak.sh` | вход через Keycloak: realm, direct access grant, синхронизацию ролей, блокировку |
| `e2e-portal-access-kc.sh` | порталы через Bearer-токен Keycloak (CRUD) |
| `e2e-notifications-kc.sh` | уведомления через Bearer-токен Keycloak |
| `e2e-ratelimit.sh` | бакеты рейт-лимитов, `429` и `Retry-After`; в `e2e-all` не входит — логика покрыта `RateLimitServiceTest` |

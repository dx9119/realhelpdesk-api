# Анализ инфраструктуры RealHelpDesk (infra18)

Ветка анализа: `infra/analysis-18` (создана от `prod`).
Дата анализа: 18.10.2026. Состояние: `prod` + 1 незакоммиченный коммит, staged-набор seed-фикстур и guard-тестов.

---

## 1. Обзор проекта

**RealHelpDesk** — REST API системы управления заявками (порталы, заявки, сообщения, вложения, оповещения). Монолит-приложение на Java 21 / Spring Boot 4.1.1, весь рантайм-стек оркестрирован Docker Compose.

| Параметр | Значение |
|---|---|
| Язык / сборка | Java 21, Maven 3.9 (`pom.xml`, spring-boot-starter-parent 4.1.1) |
| Фреймворк | Spring Boot 4 (Web, Security, Data JPA, Validation, Mail, OAuth2 Resource Server, Actuator, OpenTelemetry) |
| БД | PostgreSQL 18, миграции Liquibase, `ddl-auto=validate` |
| Хранилище файлов | MinIO (`pgsty/silo`-образ) |
| Аутентификация | Keycloak (OAuth2 RS, профиль `keycloak`) **или** встроенный JWT (jjwt, http-only cookies) |
| Почта | Внешний SMTP; в dev — smtp4dev, в IT — GreenMail |
| Объём кода | ~242 main + ~92 test классов (из них ~10 `*IT.java`) |
| Frontend | в репозитории **нет** (`make openapi-types` готовит `web/src/api/schema.d.ts` для отдельного фронта) |

---

## 2. Топология запуска: один compose-файл + профили

Единственный файл — `infrastructure/docker-compose.yaml` (имя проекта `realhelpdesk`, тома `realhelpdesk_*`). Различия сред живут **не в разных compose-файлах**, а в `infrastructure/.env` + наборе compose-профилей, которые передаёт Makefile.

### 2.1 Compose-профили

| Профиль | Сервисы | Назначение |
|---|---|---|
| `infra` | `postgres` (postgres:18, том `postgres_data`), `minio` (Silo/MinIO, том `minio_data`) | Данные. Сеть `data` — `internal: true`, портов на хост **нет** |
| `dev` | `db-gateway` (socat: 127.0.0.1:5432/9000/9001 → postgres/minio), `smtp` (smtp4dev :25/:3000), `keycloak-db-init`, `keycloak` (127.0.0.1:8180) | Хостовой доступ к БД/MinIO, локальная почта, локальный Keycloak |
| `app` | `realhelpdesk-app` (собственный образ, порты 8443 + 127.0.0.1:8081) | Приложение |
| `monitoring` | `mimir` (:9090), `grafana` (:3001), `tempo` (:3200), `loki` (:3100), `otel-collector`, `alloy` | M3-стек (метрики/трейсы/логи) |
| `load` | `k6` | Нагрузочный user journey |

Сети: `data` (internal, изолированы БД/хранилище) и `backend` (app+smtp+наблюдаемость+k6).

### 2.2 Наборы профилей (Makefile → compose)

| Make-цель | Профили | Что получаем |
|---|---|---|
| `make up` | infra+dev+app | Полный локальный dev-стек, в фоне |
| `make dev-run` | infra+dev+app | То же, foreground с логами всех сервисов |
| `make up-prod` | infra+app | Рабочий контур (без dev/monitoring/Keycloak) |
| `make obs-up` | infra+dev+app+monitoring | Dev-стек + наблюдаемость |
| `make up-load` | infra+dev+app (+k6 через load/run.sh) | Стек для k6, `TRUST_PROXY_HEADERS=true` инлайном |
| `make down` / `down-volumes` | все | Остановка / остановка с удалением томов |

### 2.3 Порты (хост)

| Порт | Сервис | Примечание |
|---|---|---|
| `https://localhost:8443` | app | Основной HTTPS (TLS, keystore PKCS12) |
| `http://127.0.0.1:8081` | app (actuator) | health/info/prometheus, только loopback |
| `127.0.0.1:5432` | db-gateway → postgres | Только профиль dev |
| `127.0.0.1:9000/9001` | db-gateway → minio | API / console, только профиль dev |
| `http://localhost:3000` | smtp4dev | UI тестовой почты |
| `http://localhost:8180` | keycloak | Тестовый Keycloak (start-dev) |
| `http://localhost:3001/9090/3200/3100` | grafana/mimir/tempo/loki | Только профиль monitoring |

### 2.4 Безопасность контейнера app

- `read_only: true`, tmpfs `/tmp` (64m) и `/app/ssl`; `cap_drop: ALL`, `no-new-privileges`;
- пользователь `spring-user` (uid/gid 10001) — фиксирован под tmpfs/тома;
- `mem_limit`/`memswap_limit` 768m; JVM в `-XX:MaxRAMPercentage=45` (≈345M heap) либо жёсткий `-Xmx` через `JAVA_XMX`;
- секреты приходят только через env (`${VAR:?}` в compose валит пустой секрет), keystore генерируется **при старте контейнера** (`entrypoint.sh` + `keytool`), не при сборке;
- healthcheck — TCP-проба `bash /dev/tcp` на 8443, `start_period` 60s;
- `check-image.sh` (make check-image): docker history/env/FS/jar без секретов, опционально trivy.

---

## 3. Сборка и компиляция

### 3.1 Что компилируется (Maven, `pom.xml`)

Аннотационные процессоры (порядок в `maven-compiler-plugin` значим):

1. **MapStruct** (`mapstruct-processor`) — генерирует `XxxMapperImpl` из интерфейсов `@Mapper`;
2. **Lombok** — геттеры/сеттеры/конструкторы/`@Slf4j` (поле `logger` — задаётся `lombok.config`, **обязателен в контейнерной сборке**: без него `@Slf4j` создал бы `log` и сломал бы код, обращающийся к `logger`);
3. **lombok-mapstruct-binding** — «примиряет» Lombok и MapStruct (иначе маппер не видит сгенерированные аксессоры).

Сборка также гоняет на phase `validate`:
- **Spotless** (`spotless:check`, eclipse-формат `config/eclipse/eclipse-format.xml`, 4 пробела, line length 140, порядок импортов `java,javax,jakarta,org,com`);
- **Checkstyle** (`config/checkstyle/checkstyle.xml`, fail on error, включая test-исходники).

Артефакты Maven:
- `mvn package` → fat-jar `target/realhelpdesk-0.0.1-SNAPSHOT.jar` (Spring Boot loader layers);
- `liquibase-maven-plugin` — ручные миграции с хоста (`make migrate` / `make db-seed`);
- surefire — unit-тесты (исключает `**/*IT.java`); failsafe — интеграционные `*IT` (GreenMail); в обоих `-javaagent:mockito-core`.

### 3.2 Сборка Docker-образа (`Dockerfile`)

Двухэтапная:

1. **Build** (`maven:3.9.6-eclipse-temurin-21`): `pom.xml` + `src` + `config` + `lombok.config` → `mvn package -DskipTests` с BuildKit-кэшем `/root/.m2` (кэш переживает правки pom); затем `java -Djarmode=tools -jar app.jar extract --layers` — jar раскладывается на слои (dependencies → snapshot-dependencies → spring-boot-loader → application);
2. **Runtime** (`eclipse-temurin:21-jre`): непривилегированный пользователь, слои jar копируются по одному (кэш слоёв: зависимости меняются только при правке pom), `ENTRYPOINT scripts/docker/entrypoint.sh` (keystore → `exec java` с JVM-флагами из ENV).

`GIT_SHA` передаётся build-arg → LABEL `org.opencontainers.image.revision`.

**Важно:** собирать jar можно и на хосте (Java 21 + Maven), и внутри Docker. Требование **Java 21** — в окружении разработчика может стоять более новый JDK (например 26), целевой `-source/-target 21`.

### 3.3 Продукты компиляции/сборки

| Продукт | Кто делает | Когда |
|---|---|---|
| `target/*.jar` | Maven (`package`) | на хосте (dev-цикл) или в stage1 Dockerfile |
| `XxxMapperImpl` | MapStruct processor | компиляция |
| Геттеры/сеттеры/`logger` | Lombok | компиляция |
| `realhelpdesk:latest` | Docker build (make up/docker-build) | compose up --build |
| `openapi.json` / `schema.d.ts` | springdoc + npx openapi-typescript | make openapi(-types), нужен живой стек |

---

## 4. Конфигурация: как устроена

### 4.1 Принцип «схема без значений»

`src/main/resources/application.properties` и `application-domains.properties` содержат **только** обязательные `${VAR}` **без дефолтов**. Отсутствующая переменная валит старт с `Could not resolve placeholder '<VAR>'` — тихих подстановок нет. Исключение, закреплённое в коде: `spring.jpa.hibernate.ddl-auto=validate` (env не управляется).

### 4.2 Три места хранения значений

| Файл | Роль |
|---|---|
| `infrastructure/.env.example` | Шаблон в git: рабочие дефолты для локальной разработки + пустые строки-напоминания секретов + закомментированная секция «Рабочий контур» |
| `infrastructure/.env` | Рабочий файл разработчика (не в git). Одновременно: (1) `env_file` контейнеров app/alloy, (2) источник `${VAR}`-интерполяции compose |
| `services.app.environment` в compose | Мост compose → app для секретов (`${VAR:?}`) и трёх динамических ключей (`OTLP_TRACING_ENDPOINT`, `TRUST_PROXY_HEADERS`, `CLIENT_IP_FORWARDED_INDEX`) |

Парность схемы (`.env.example` ↔ `application*.properties` ↔ compose) контроллирует `ApplicationConfigSchemaTest` (в `make test`); «деструктивный» `ddl-auto` не может появиться в env — `NON_SCHEMA_KEYS` в том же тесте.

### 4.3 Синхронизация `.env` — `make env` (`scripts/docker/sync-env.sh`)

1. создаёт `.env` из шаблона, если файла нет;
2. дописывает недостающие ключи с дефолтами шаблона;
3. обновляет значения, которые всё ещё равны **прежнему дефолту** (сверка по снимку `infrastructure/.env.defaults`);
4. **не трогает** пользовательские значения/секреты (только warn);
5. обновляет снимок `.env.defaults`.

Это ключ к «многократному и быстрому» запуску: меняешь дефолты в шаблоне для всей команды — каждый разработчик применяет одной командой `make apply-config` без потери секретов.

### 4.4 Профили приложения

`SPRING_PROFILES_ACTIVE` живёт **только** в `infrastructure/.env` (env_file контейнера перекрывает окружение хоста).

| Профиль | Файл | Что даёт |
|---|---|---|
| `dev` | `application-dev.properties` | DEBUG по приложению, SQL-логи Hibernate без bind-параметров, статистика Hibernate |
| `prod` | `application-prod.properties` | INFO/WARN, JSON-логи (ECS), инварианты: SQL off, миграции on, без текста исключений в ошибках; плюс `ProdPreflightValidator` |
| `keycloak` (второй) | `application-keycloak.properties` | OAuth2 Resource Server: JWK/issuer, sync-roles, auto-create-users; cookie-auth-эндпоинты `@Profile("!keycloak")` отключаются |

Значения по умолчанию в шаблоне: `dev,keycloak` (по умолчанию токены выпускает локальный Keycloak). Переключение:

```bash
make profile-local     # SPRING_PROFILES_ACTIVE=dev           — внутренний JWT (cookie /api/v1/auth/*)
make profile-keycloak  # SPRING_PROFILES_ACTIVE=dev,keycloak  — Keycloak
```

`scripts/docker/set-profile.sh`: правит `.env`; если профиль уже тот — выходит без пересоздания (идемпотентно, удобно для e2e-зависимостей); иначе `up -d --force-recreate --no-deps app` и ждёт health до 180 с. `NO_RESTART=1` — только записать профиль.

### 4.5 Prod-preflight (`ProdProfileValidator`, профиль `prod`)

Старт валится при: выключенной капче, `SameSite=None` без домена cookie, localhost в CORS, выключенных миграциях или seed-контексте, самоподписанном keystore `CN=localhost`, почте на локальном SMTP (`localhost`/`127.0.0.1`/`smtp`), пустом `MAIL_FROM`, `SMTP_AUTH=true` без `MAIL_USER`. В `dev` компонент не создаётся.

### 4.6 Миграции (Liquibase)

- changelog: `src/main/resources/db/changelog/db.changelog-master.xml` (+ `changes/` DDL, `seed/` фикстуры);
- приложение применяет миграции при старте (`DB_MIGRATIONS_ENABLED=true`), Hibernate только `validate`;
- контекст `DB_MIGRATIONS_CONTEXTS=schema` (дефолт) — только структура; `seed` — демо-данные (context=`seed`, идемпотентные INSERT'ы), применяются только `make db-seed` или явным контекстом;
- охрана «в changes/ только DDL» — `LiquibaseChangelogGuardTest`;
- ручной прогон с хоста: `make migrate` / `make db-seed` (Liquibase CLI через localhost:5432 от db-gateway);
- чистая БД: `make down-volumes && make up`.

---

## 5. Что помогает в работе разработчика

### 5.1 Makefile — единая точка входа

`make help` — список целей. Основные группы:

| Группа | Цели |
|---|---|
| Сборка/тесты на хосте | `build` (package -DskipTests), `compile`, `package`, `test` (surefire), `verify` (+*IT failsafe), `clean` |
| Стиль | `format` (spotless:apply), `check` (spotless:check + checkstyle) |
| Стек | `env`, `up`, `dev-run`, `up-prod`, `down`, `down-volumes`, `ps`, `logs`, `restart`, `shell` |
| Быстрые правки на лету | `apply-config` (sync .env + пересоздать app без сборки), `profile-local`, `profile-keycloak` |
| Миграции | `migrate`, `db-seed` |
| E2E | `e2e-portal-access`, `e2e-ratelimit`, `e2e-jwt`, `e2e-notifications`, `e2e-portal-transfer`, `e2e-token-cleanup`, `e2e-keycloak`, `e2e-all`, `dev-test` |
| Наблюдаемость | `obs-up`, `obs-down`, `obs-open`, `obs-reload`, `obs-alerts` |
| Нагрузка | `up-load`, `load`, `load-smoke` |
| Контракт | `openapi`, `openapi-types` |
| Образ | `docker-build`, `check-image` |

### 5.2 Скрипты (`scripts/`)

| Скрипт | Назначение |
|---|---|
| `docker/sync-env.sh` | Синхронизация `.env` с шаблоном (make env/apply-config) |
| `docker/set-profile.sh` | Переключение `SPRING_PROFILES_ACTIVE` + пересоздание app с ожиданием health |
| `docker/entrypoint.sh` | Рантайм: генерация keystore при старте, JVM-флаги, exec java |
| `docker/check-image.sh` | Аудит собранного образа на секреты/материал |
| `e2e-*.sh` (7 шт.) | HTTP-сценарии на живом стеке (curl + cookie jar + smtp4dev REST) |
| `load/run.sh`, `user-journey.js`, `solve-pow.js` | k6: сидирование пула через API (с решением PoW-капчи) + user journey с порогами |
| `sql/` | Разовые SQL для аудита |

### 5.3 Тесты

| Уровень | Команда | Инфраструктура |
|---|---|---|
| Unit + срезы | `make test` | H2 (`@DataJpaTest`), Mockito (агент), тестовая конфигурация `src/test/resources/application.properties` (затеняет прод-схему) |
| Интеграционные `*IT` | `make verify` | GreenMail (in-memory SMTP), failsafe |
| Архитектурные охранители | внутри `make test` | `ApplicationConfigSchemaTest`, `LiquibaseChangelogGuardTest`, `NPlus1GuardTest`, `ProdPreflightValidatorTest` |
| E2E | `make e2e-*` | Живой compose-стек, внутренний JWT-контур (кроме e2e-keycloak) |
| Нагрузочные | `make load` | k6, пороги p95/RPS, nonzero exit при провале |

Тесты **не** ходят в Docker и в других средах не запускаются — только на хосте в dev.

### 5.4 Наблюдаемость (make obs-up)

Метрики: Micrometer → `/actuator/prometheus` (8081, HTTP Basic `METRICS_USER/METRICS_PASSWORD`) → Alloy скрейпит → remote_write в Mimir. Трейсы: OTel → otel-collector → Tempo (endpoint подставляет make obs-up инлайном). Логи: stdout → Alloy (фильтр по compose-метке service=app) → Loki. UI: Grafana с provisioning datasources/dashboards; 8 алертов Mimir с runbooks в `docs/runbooks/`.

### 5.5 CI (GitHub Actions, `.github/workflows/ci.yml`)

Три job'а: (1) `mvn spotless:check checkstyle:check verify`; (2) hadolint + docker build + trivy (CRITICAL/HIGH) + gitleaks; (3) e2e: генерация временных секретов в `.env` → `make up` → ожидание health app и Keycloak → `make e2e-all`. Renovate — обновления зависимостей и docker-тегов.

### 5.6 Документация

- `README.md` — единая точка правды (быстрый старт, конфигурация, профили, капча, доступы, уведомления, API, rate limit, наблюдаемость, тесты);
- `docs/api.md` — эндпоинты; `docs/capcha.md` — капча; `docs/code-style.md` — стиль;
- `docs/observability/{overview,metrics,slo}.md`; `docs/runbooks/*.md` (8 шт.);
- `infrastructure/observability/README.md` — состав obs-стека;
- PR template: `.github/PULL_REQUEST_TEMPLATE.md`.

---

## 6. Флоу запуска для разработки (многократный, простой, быстрый)

### 6.1 Первый запуск (однократно на машине)

```bash
# Требования: Docker + Compose v2, GNU Make, curl, jq; Java 21 + Maven — только для тестов/сборки на хосте
make env                          # создать infrastructure/.env из шаблона
$EDITOR infrastructure/.env       # заполнить ТОЛЬКО секреты (см. таблицу ниже)
make up                           # infra+dev+app, сборка образа, в фоне
# либо make dev-run — foreground с логами всех сервисов, Ctrl+C — стоп

# Проверка
curl -sk https://localhost:8443/api/v1/health        # 200
open https://localhost:8443/swagger-ui.html
```

Обязательные секреты в `.env` (остальное уже с рабочими дефолтами):

| Секрет | Чем заполнить |
|---|---|
| `JWT_SECRET` | `openssl rand -base64 32` |
| `CAPTCHA_HMAC_SECRET` | `openssl rand -base64 32` |
| `DB_PASSWORD` | любой пароль |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | учётка MinIO |
| `METRICS_PASSWORD` | любой непустой пароль |

Первый `make up` медленный (сборка образа + pull образов + старт Keycloak/Postgres ≈ несколько минут). Все последующие — быстрые.

### 6.2 Повторные запуски и ежедневный цикл (самый частый сценарий)

Стек **не нужно пересобирать** при каждой правке кода/конфига — тома и сети живут между `down`/`up`; контейнеры пересоздаются точечно.

```bash
make up                 # вернуться к работе после остановки (секунды, если образ уже собран)
make restart            # только перезапуск JVM внутри app (быстрый цикл «правка конфига в .env»)
make apply-config       # правка конфига/лимитов: sync .env + пересоздать app БЕЗ пересборки
make logs               # логи app (follow); make ps — статус; make shell — sh в app
make down               # остановить (тома с данными целы) — возврат за секунды
make down-volumes       # редко: полный сброс данных БД/MinIO
```

**Сценарии правок:**

| Что менял | Команда применения | Скорость |
|---|---|---|
| Java-код | `make up` (compose пересоберёт слой app; слои зависимостей из кэша) либо правка в IDE → `make restart` контейнера с bind-mount не используется — код живёт в образе, поэтому: пересборка | ~1–2 мин |
| `infrastructure/.env` (значение) | `make apply-config` или `make restart` | секунды |
| Новый дефолт в `.env.example` для команды | коммит шаблона; у себя `make env` (+ `make apply-config`) | секунды |
| `pom.xml` / зависимости | `make up` (пересборка) | дольше (m2-кэш BuildKit помогает) |
| Liquibase-changelog | рестарт app (миграции при старте) или `make migrate` | секунды |
| Compose/Dockerfile | `make up` (пересборка) | по объёму правки |

**IDE-разработка (hot-loop без Docker для кода):** поднять только инфраструктуру (`docker compose -f infrastructure/docker-compose.yaml --profile infra --profile dev up -d`), в `.env`/IDE-конфиге указать `DB_URL=jdbc:postgresql://localhost:5432/desk...` (порт публикует db-gateway) и запускать приложение из IDE (`make run` = `mvn spring-boot:run`). Требует JDK 21 и переменных окружения приложения (взять из `infrastructure/.env` + переименования секретов `DB_PASS`/`MINIO_*` — см. compose `services.app.environment`). Ключ `keycloak` требует запущенного сервиса keycloak (порт 8180 проброшен) — либо запускать без него (внутренний JWT).

### 6.3 Контур аутентификации в dev

```bash
# По умолчанию (dev,keycloak): получить токен и работать с API
TOKEN=$(curl -s -X POST http://localhost:8180/realms/realhelpdesk/protocol/openid-connect/token \
  -d grant_type=password -d client_id=realhelpdesk-api \
  -d username=user@example.com -d password=user-pass | jq -r .access_token)
curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:8443/api/v1/users/profile

make profile-local    # переключиться на внутренний JWT (cookie, /api/v1/auth/*) — нужно для e2e/load
make profile-keycloak # вернуть Keycloak
```

Тестовые пользователи Keycloak: `user@example.com`/`user-pass` (ROLE_USER), `operator@example.com`/`operator-pass` (ROLE_OPERATOR). Регистрация во внутреннем контуре подтверждается кодом из smtp4dev (`http://localhost:3000`).

### 6.4 Фикстуры и сброс данных

```bash
make db-seed                     # демо-пользователи/портал/заявка (контекст seed, идемпотентно)
make down-volumes && make up     # чистая схема
make restart                     # рестарт не трогает данные
```

### 6.5 Проверки перед коммитом

```bash
make format    # привести формат
make check     # spotless:check + checkstyle
make test      # unit + архитектурные охранители
make verify    # + интеграционные *IT
# при необходимости на поднятом стеке:
make e2e-all   # все e2e (тратит рейт-лимиты; при падении — make restart)
```

CI повторит `spotless/checkstyle/verify` + e2e на чистом стеке.

### 6.6 Наблюдаемость и нагрузка (по мере надобности)

```bash
make obs-up && make obs-open     # Grafana :3001, Mimir :9090
make load-smoke                   # 2 VU, 1 минута
TARGET_VUS=10 DURATION=5m make load
make obs-alerts                   # состояния алертов
```

### 6.7 Итоговая рекомендация «просто и быстро» для многократного запуска

1. **Один раз:** `make env` → заполнить 6 секретов → `make up`.
2. **Каждый день:** `make up` (секунды, тома целы).
3. **Правки кода в контейнере:** `make up` (docker layer cache) — или IDE-цикл с `--profile infra --profile dev` + `spring-boot:run` для максимальной скорости компиляции.
4. **Правки конфига:** `make apply-config` / `make restart` — без пересборки.
5. **Чистый слэйт:** только `make down-volumes` (редко).
6. **Профиль аутентификации** менять только через `make profile-local`/`profile-keycloak` (скрипт идемпотентен и ждёт health).

---

## 7. Что уже работает в текущем окружении (контекст анализа)

На анализируемой машине поднят **полный dev-стек со всеми профилями** (12 контейнеров `realhelpdesk-*`: app, postgres, minio, db-gateway, smtp, keycloak + весь monitoring: grafana, mimir, tempo, loki, alloy, otel-collector). Это означает:

- `infrastructure/.env` заполнен (секреты на месте);
- образ `realhelpdesk:latest` собран, `target/` содержит собранный jar (110M);
- переключения профилей/e2e/нагрузка могут запускаться без предварительного `make up`.

В git: ветка `prod` опережает `origin/prod` на 1 коммит; staged — seed-фикстуры (`db/changelog/seed/100-demo-data.*`), `LiquibaseChangelogGuardTest`, правки `ProdPreflightValidator(Test)`, Makefile/README/.env.example (работа по демо-данным, перенесённая в ветку `infra/analysis-18` вместе с checkout).

---

## 8. Сводная карта файлов инфраструктуры

```
RealHelpDesk/
├── Makefile                          # все быстрые команды (точка входа разработчика)
├── Dockerfile                        # 2-этапная сборка: Maven build → JRE runtime (layers)
├── pom.xml                           # Spring Boot 4.1.1, Java 21, spotless/checkstyle/liquibase
├── lombok.config                     # logger вместо log; @Generated; критичен для контейнерной сборки
├── renovate.json                     # автообновление зависимостей
├── .github/workflows/ci.yml          # build / docker-scan / e2e
├── config/
│   ├── checkstyle/checkstyle.xml
│   └── eclipse/eclipse-format.xml    # формат для spotless
├── infrastructure/
│   ├── docker-compose.yaml           # единый compose: профили infra/dev/app/monitoring/load
│   ├── .env.example                  # шаблон (в git)
│   ├── .env                          # рабочий (не в git)
│   ├── .env.defaults                 # снимок дефолтов для sync-env
│   ├── keycloak/realhelpdesk-realm.json
│   └── observability/{mimir,grafana,tempo,loki,otel-collector,alloy}/
├── scripts/
│   ├── docker/{sync-env,set-profile,entrypoint,check-image}.sh
│   ├── e2e-*.sh                      # 7 HTTP-сценариев
│   └── load/{run.sh,user-journey.js,solve-pow.js}
├── src/main/resources/
│   ├── application.properties        # схема ${VAR} без дефолтов
│   ├── application-domains.properties
│   ├── application-{dev,prod,keycloak}.properties
│   ├── db/changelog/                 # liquibase: changes/ + seed/
│   ├── logback-spring.xml, messages.properties, static/
└── docs/                             # api, capcha, code-style, observability, runbooks
```

---

## 9. Наблюдения / потенциальные зоны для дальнейшего анализа

1. **Скорость повторного запуска кода.** Код приложения живёт **только в образе** — любая правка Java требует пересборки docker-слоя (buildkit-кэш m2 помогает, но layer app пересобирается). Для «горячего» цикла предложен IDE-вариант (§6.2), но он не описан в README как штатный путь и требует ручной подстановки env. Возможное улучшение: bind-mount classes/devtools или закомментированный dev-профиль в compose.
2. **Двойственность `.env`.** Один файл — и env_file, и источник интерполяции compose; секреты дублируются через `services.app.environment` (`DB_PASSWORD`→`DB_PASS`, `MINIO_ROOT_*`→`MINIO_ACCESS_KEY/SECRET_KEY`). Управляемо (охраняет тест), но увеличивает когнитивную нагрузку при добавлении нового секрета (три места: example, .env, compose).
3. **E2E тратят рейт-лимиты** (in-memory счётчики) — при частых прогонах нужен `make restart`; `e2e-all` переключает профиль туда-сюда (dev → dev,keycloak), что пересоздаёт контейнер дважды.
4. **Тестовая конфигурация затеняет prod-схему** (`src/test/resources/application.properties`) — при добавлении новой обязательной переменной в `application.properties` нужно помнить о тестовом файле/`application-test.properties` (иначе unit-тесты упадут на placeholder).
5. **MinIO-образ** — `pgsty/silo:RELEASE.2026-09-16...` (форк/релизная линия MinIO); версии всех obs-сервисов закреплены (не latest) — обновления через Renovate с ревью.
6. **Postgres 18 нюанс монтирования** — том на `/var/lib/postgresql` (не `/data`), иначе образ считает том остатком старой версии.
7. **Java на хосте ≠ Java цели.** В окружении анализа JDK 26 + Maven 3.9.11; проект таргетит 21. Контейнерная сборка изолирована от этого (temurin-21); сборка на хосте возможна при toolchain/`java.version=21`, но рантайм-совместимость надо держать в голове.
8. **Нет mvnw** (wrapper в .gitignore) — Maven должен быть установлен глобально; версия в CI: через setup-java cache maven.

---

# Часть II. План улучшений dev / prod / CI

Цель: **dev-среда, которая запускается быстро и предсказуемо**, и **prod-профиль, который CI приводит на сервер безопасным, воспроизводимым способом**. Проект сейчас в стадии разработки — «продовой» эксплуатации нет, поэтому prod-цепочка строится «на вырост», но уже сейчас, чтобы не переигрывать её потом.

---

## 10. Целевая модель (goal state)

### 10.1 Dev (разработка)

| Свойство | Целевое состояние |
|---|---|
| Bootstrap | Одна команда: `make dev-setup` (env + секреты-заглушки + up) либо devcontainer — «открыл → работает» |
| Внутренний цикл правки кода | Секунды, без пересборки docker-образа: приложение на хосте (IDE/`spring-boot:run`) против dockerized-инфраструктуры, либо DevTools/restart в контейнере |
| Итерация «конфиг» | `make apply-config` / `restart` — без пересборки (уже есть) |
| Тесты | `make test`/`verify` на хосте без Docker (уже есть); e2e — на поднятом стеке |
| Сброс | `make down-volumes` — редко; данные переживают restart (уже есть) |

### 10.2 Prod / CD (безопасное обновление на сервере)

| Свойство | Целевое состояние |
|---|---|
| Артефакт | **Один раз** собирается в CI: образ `ghcr.io/<org>/realhelpdesk:<semver>+<sha>` (или по sha), пушится в registry, сканируется. На сервер образ **не собирается** — только `pull` |
| Продвижение | Тот же digest идёт dev → staging → prod (build once, deploy many); запрет пересборки на сервере |
| Конфигурация сервера | Отдельный env/секреты сервера (не `infrastructure/.env` разработчика); секреты — из secret manager/CI secrets/SSH-vault, не из git |
| Миграции | Отдельный шаг деплоя **до** старта нового кода (или at-startup с expand-contract совместимостью); откат кода ≠ откат схемы |
| Развёртывание | `docker compose pull && up -d app` (или k8s/systemd) с health-gate; неудачный health → откат на предыдущий тег |
| Контур | `SPRING_PROFILES_ACTIVE=prod` (+`prod,keycloak` при внешнем Keycloak); `ProdPreflightValidator` — последний рубеж (уже есть) |
| Наблюдаемость | `make up-prod` уже не тянет dev/monitoring — метрики/алерты на сервере своим стеком или SaaS |

### 10.3 CI (три контура, не один job)

1. **PR-pipeline** — линтеры, unit/IT, сборка образа, сканы (быстрый фидбек, без деплоя);
2. **Main/prod-pipeline** — то же + push immutable-образа в registry с тегами;
3. **Deploy-pipeline** — по тегу/manual approval: pull на сервер, миграции, health-gate, smoke.

---

## 11. Общепринятые практики vs текущее состояние

Легенда: ✅ — соответствует, ⚠️ — частично, ❌ — расходится.

### 11.1 Dev-среда

| Практика | Статус | Комментарий по проекту |
|---|---|---|
| Одна команда bootstrap | ⚠️ | `make env` + ручное заполнение 6 секретов + `make up`. Нет «заводского» режима с auto-gen секретов для локальной разработки (в CI они генерируются, локально — руками) |
| Hot reload / быстрый inner loop | ❌ | Код живёт только в образе: правка Java → rebuild docker-слоя. Spring Boot DevTools не подключён. IDE-путь (db-gateway + `spring-boot:run`) существует фактически, но не оформлен |
| Инфраструктура в контейнерах, код — на хосте | ⚠️ | Поддержано (`db-gateway`, loopback-порты), но штатной compose-цели «только infra, без app» нет — придётся руками `--profile infra --profile dev` |
| Тесты без Docker | ✅ | `make test`/`verify` на хосте (H2, GreenMail) |
| Единый вход (Makefile) | ✅ | `make help`, исчерпывающий набор целей |
| Воспроизводимость сборки | ⚠️ | Нет Maven Wrapper (mvnw в .gitignore) — версия Maven зависит от машины; JDK-версия хоста может не совпадать с 21 |
| Конфиг-менеджмент без потери секретов | ✅ | `sync-env.sh` + снимок `.env.defaults` — редкая и сильная сторона проекта |
| Devcontainer / codespace | ❌ | Нет `.devcontainer` — новый разработчик проходит путь руками |
| Демо-данные | ⚠️ | Seed только по явной команде (`make db-seed`) — это **плюс** для чистоты, но нет «демо-режима одной кнопкой» |

### 11.2 Сборка и артефакты

| Практика | Статус | Комментарий |
|---|---|---|
| Build once, deploy many | ❌ | `make up-prod` = `up --build` **на сервере**: jar собирается в Dockerfile на целевой машине. CI собирает образ для проверок, но не пушит и не промотирует его |
| Immutable-теги образа | ❌ | Всегда `realhelpdesk:latest`. Есть LABEL `GIT_SHA`, но нет семантического тега (`v1.2.3`, `sha-abcdef`) и registry |
| Версия артефакта | ❌ | `0.0.1-SNAPSHOT` навсегда — нельзя сослаться на релиз в deploy/rollback |
| Скан уязвимостей до поставки | ✅ | trivy + hadolint + gitleaks в CI; `make check-image` локально |
| Слоистый кэш, non-root, minimal runtime | ✅ | Отработано грамотно (layers, uid 10001, read_only) |
| Закрепление base-образов | ⚠️ | Теги закреплены, но не по digest (`postgres:18` vs `postgres:18@sha256:…`) |

### 11.3 CI

| Практика | Статус | Комментарий |
|---|---|---|
| CI на ветке по умолчанию / PR | ❌ | `ci.yml` триггерится на `main`, а **дефолтная ветка репозитория — `prod`** (`origin/HEAD -> origin/prod`). Пуши в `prod` не запускают CI; `main` выглядит мёртвой веткой. Прямое расхождение с реальным workflow |
| Разделение PR / release / deploy | ❌ | Один workflow, только проверки; нет release и deploy |
| Кэш сборки в CI | ✅ | `actions/setup-java cache: maven`; docker layer cache в сборке образа CI — дефолтный (без buildx cache-from/to, но терпимо) |
| Секреты CI | ✅ | Генерируются на лету, не хранятся; `MAIL_PASSWORD` осознанно пуст |
| E2E на каждый PR | ⚠️ | Полный `make up` + `e2e-all` (~до 30 мин timeout) — надёжно, но дорого; нет smoke-режима PR vs полного main |
| Protected environments / approvals | ❌ | Нет deploy-job → нет environment protection |
| Актуальность веток | ⚠️ | Живут `prod` (дефолт), `dev`, `main` + ~30 feature-веток; назначение `main` не формализовано |

### 11.4 Prod-контур и безопасность деплоя

| Практика | Статус | Комментарий |
|---|---|---|
| Prod-preflight конфигурации | ✅ | `ProdPreflightValidator` — сильный, нестандартно хороший барьер (капча, CORS, keystore, SMTP, миграции) |
| Инварианты, закреплённые в коде | ✅ | `ddl-auto=validate` и prod-перекрытия (show-sql off, liquibase on) нельзя вернуть env'ом |
| Разделение dev/prod конфигурации | ✅ | Профили + секция «Рабочий контур» в шаблоне; dev-сервисы не входят в `up-prod` |
| Секреты на сервере | ⚠️ | Ожидается plaintext `infrastructure/.env` на хосте; нет варианта docker secrets/SOPS/age/vault |
| Keystore в prod | ⚠️ | Смонтированный файл поддержан, но фолбэк — самоподпись при старте (preflight отловит `CN=localhost`, но не «старый» самоподписанный серт) |
| Миграции как шаг деплоя | ⚠️ | Миграции применяет **приложение при старте** — допустимо при expand-contract, но нет отдельной команды/джобы «миграции → then rollout», нет ручки отката схемы |
| Откат | ❌ | Нет тегов/registry → откат = пересборка «как было» (недетерминированно); нет playbook rollback |
| Smoke после деплоя | ⚠️ | Health-gate есть (compose healthcheck + start_period); smoke по API (санити-сценарий) — нет |
| Наблюдаемость прода | ⚠️ | Стек есть, но `up-prod` его не включает; алерты/runbooks описаны для dev-стенда |

### 11.5 Тестирование vs прод

| Практика | Статус | Комментарий |
|---|---|---|
| Тесты на том же движке БД, что и прод | ❌ | Все JPA-слайсы и `@SpringBootTest` — на **H2** (`create-drop`); прод — PostgreSQL 18. Расхождения SQL-диалекта, JSONB, `reWriteBatchedInserts`, seq/identity поймают только e2e/нагрузка |
| Testcontainers | ❌ | Не подключён; естественный способ закрыть H2↔PG-разрыв |
| Контракт API (OpenAPI) | ✅ | Спека из кода + `make openapi-types`; нет правки спеки руками |
| E2E изолированы от prod-контура | ✅ | Внутренний JWT-контур + временные пользователи |

---

## 12. Приоритизированный план улучшений

Оценка трудозатрат: S — часы, M — 1–3 дня, L — неделя+. Приоритет: P0 — блокирует цель, P1 — сильно мешает, P2 — качественный рост.

### Этап 0. Расчистка фундамента (P0, быстро)

| # | Действие | Зачем | Трудоёмкость |
|---|---|---|---|
| 0.1 | **Привести CI в соответствие с git.** Либо `on.push.branches: [prod, main]` (+ PR), либо объединить `main`/`prod` (рекомендуется: одна интеграционная ветка `main` или `prod`, вторая — не нужна). Добавить триггер на все PR | Сейчас деплой-готовая ветка `prod` живёт **мимо CI** | S |
| 0.2 | **Убрать сборку на сервере из prod-флоу.** Временно: на сервере только `docker compose pull` + `up` (см. 2.2). Документировать «на сервере не собирать» | Immutable artifact — базовая практика; текущий `make up-prod` её нарушает | S |
| 0.3 | **Ввести реальную версию.** `pom.xml` — semver (0.1.0 → 0.2.0 …), CI прокидывает тег в образ (`IMAGE_TAG`), git-tag → release | Без версии нет отката и трассируемости | S |
| 0.4 | **Maven Wrapper в репозиторий** (убрать mvnw из .gitignore, закоммитить wrapper) | Воспроизводимая версия Maven у всех и в CI | S |
| 0.5 | **Секреты для первого локального запуска.** `make dev-setup`: если секреты пусты — сгенерировать `openssl rand`, написать в `.env` с пометкой «локальные, не для прода» | Снимает ручной шаг bootstrap; в CI уже так делается | S |

### Этап 1. Быстрый dev-inner-loop (P1)

| # | Действие | Зачем | Трудоёмкость |
|---|---|---|---|
| 1.1 | **Compose-цель «инфра без приложения».** `make infra-up` = `--profile infra --profile dev up -d` (postgres, minio, db-gateway, smtp, keycloak) + документировать IDE-запуск (`spring-boot:run` + DB_URL=localhost:5432, ключи из `.env`, переименования `DB_PASS`/`MINIO_*`) | Правка Java без docker-rebuild: секунды вместо минут. Уже поддержано топологией (`db-gateway`) — не хватает только цели и инструкции | S |
| 1.2 | Опционально: **Spring Boot DevTools** в `dev`-профиле (restart на изменение classpath) для контейнерного пути | Альтернатива 1.1 для тех, кто любит всё в Docker | S |
| 1.3 | **Тесты БД на PostgreSQL.** Подключить Testcontainers для `*IT`/`@DataJpaTest` (переключение `spring.datasource` с H2 на container), оставив H2 только для быстрых слайсов либо отказавшись от H2 | Прод-диалект сейчас не покрыт тестами до e2e | M |
| 1.4 | **Smoke-режим e2e для PR** (`E2E_SET=smoke make e2e-all` — 2–3 сценария) и полный набор — на merge в интеграционную ветку | Ускорение PR-цикла без потери покрытия main | S |
| 1.5 | Опционально: **.devcontainer** (Docker-in-Docker + Java 21 + Maven + Make) | Нулевой bootstrap для новых разработчиков / чистых машин | M |
| 1.6 | Снизить шум e2e ↔ рейт-лимиты: сброс in-memory лимитов перед прогоном (`RESET_LIMITS=1` уже есть в load; аналогично для e2e-all) | Предсказуемость повторных прогонов | S |

### Этап 2. Prod-цепочка через CI/CD (P0–P1, ядро задачи)

| # | Действие | Зачем | Трудоёмкость |
|---|---|---|---|
| 2.1 | **Registry + push образа.** В release-job (пуль на `prod`/тег `v*`): `docker buildx build --push` → `ghcr.io/.../realhelpdesk:v0.1.0` и `:sha-<git>`. CI-сканы (trivy/gitleaks) — **до** push | Immutable-артефакт = единственный источник прода | M |
| 2.2 | **Deploy-job (GitHub Environment `production`).** На сервере (SSH-Action или self-hosted runner): `docker compose pull app && docker compose up -d --no-build app` + ожидание health (curl `/actuator/health` на 8081/health-группы) + rollback: `up -d app` с предыдущим тегом при провале | Безопасное обновление: запрет сборки на сервере, health-gate, откат | M |
| 2.3 | **Секреты сервера.** Минимум: отдельный `infrastructure/.env.prod` (не в git) на сервере; заполнение — через CI-secret → scp/ansible, либо SOPS+age в git. Никакого переиспользования dev-`.env` | Сейчас prod-флоу предполагает тот же plaintext-файл | S–M |
| 2.4 | **Миграции отдельным шагом.** Job: `docker compose run --rm app liquibase update` **или** `make migrate` с prod-URL до переключения образа; правило совместимости — expand-contract (новый код работает и со старой, и с новой схемой на окно отката) | Старт-приложение-миграции допустимо, но деплой-джоба даёт контроль и лог шага; откат кода безопасен | M |
| 2.5 | **Теги и changelog.** Git tag `vX.Y.Z` → автоматически тег образа; release notes из коммитов (conventional commits / semantic-release — по желанию) | Трассируемость «что на сервере» | S |
| 2.6 | **Smoke после деплоя.** Краткий сценарий (health → login Keycloak → CRUD-портала, либо `e2e-jwt` против prod-staging) как шаг deploy-job; провал → откат | Ловит «зелёный health, сломанный бизнес» | S |
| 2.7 | **Деплой только с approval.** GitHub Environment: required reviewers, wait-timer; запрет деплоя с feature-веток | Контролируемое продвижение | S |
| 2.8 | **Compose для рабочего контура без dev-хвостов.** Файл `infrastructure/docker-compose.prod.yaml` (override) либо профиль `app`+`infra` без права сборки (`build:` убрать/отключить в override, только `image: ${APP_IMAGE}`) | Физически запретить сборку на сервере | S |

### Этап 3. Качество и эксплуатация (P2)

| # | Действие | Зачем |
|---|---|---|
| 3.1 | SBOM (syft) + подпись образа (cosign) в release-job | Supply-chain |
| 3.2 | Base-образы по digest; Renovate-группа «security patches» с авто-PR | Воспроизводимость + патчи |
| 3.3 | buildx cache (GHA cache) для docker-джобы PR | Ускорение CI |
| 3.4 | Алерты/минимальный monitoring на сервере (даже без полного obs-стека: healthcheck + внешний Uptime + alert в Telegram) | «Прод без алертов слеп» |
| 3.5 | Expand-contract гайд для миграций + guard-тест «нет destructive changeset без backward-compat пометки» | Безопасность схемы при откате кода |
| 3.6 | Документ `docs/deploy.md`: как выкатить, как откатить, где секреты, как смотреть логи на сервере | Онбординг эксплуатации |

---

## 13. Целевой pipeline (схема)

```
feature/* ──PR──► CI (lint, test, build, scan, e2e-smoke)     ──► merge gate
                        │
merge в prod/main ──────► CI (…, e2e-full) + release-job:
                          build image → trivy/gitleaks → push ghcr.io:rhd:vX.Y.Z + :sha-…
                        │
              git tag vX.Y.Z ──► Deploy (environment: production, approval):
                          SSH/server: pull → migrations job → up -d --no-build app
                          → health-gate → smoke → (fail ⇒ roll back to prev tag)
```

Dev остаётся локальным (`make up` / `make infra-up` + IDE) и **не зависит** от CD-цепочки.

---

## 14. Сводка расхождений «наибольшего смещения от практик»

Эти пункты идут в разрез с общепринятыми практиками сильнее всего и закрываются Этапами 0–2:

1. **CI не видит дефолтную ветку** (`ci.yml` → `main`, реальность → `prod`) — вся проверочная инфраструктура мертва для основного потока.
2. **Сборка артефакта на сервере** (`make up-prod` = `up --build`) вместо pull immutable-образа из registry.
3. **Нет версии/тегов/registry** — нет объекта отката, нет «что именно развёрнуто».
4. **Код только в образе** — dev-inner-loop медленнее принятого минимума (seconds-level edit-run); devtools/IDE-путь не оформлены штатно.
5. **Тесты на H2 при проде на PostgreSQL** — диалект и поведение БД не проверяются до e2e.
6. **Секреты прода** ожидаются plaintext-файлом на сервере без secret-manager-варианта.
7. **Миграции — побочный эффект старта приложения**, а не управляемый шаг деплоя с окном совместимости.

---

## 15. Что не нужно менять (сохранить как есть)

Эти решения уже соответствуют практикам — не переигрывать:

- единый compose + профили + Makefile как единая точка входа;
- `sync-env.sh` + `.env.defaults` (безопасная синхронизация конфигов);
- «схема `${VAR}` без дефолтов» + `ApplicationConfigSchemaTest`;
- `ddl-auto=validate` закреплённым литералом + Liquibase как единственный источник структуры;
- `ProdPreflightValidator` и prod-инварианты в `application-prod.properties`;
- разделение dev-сервисов (smtp4dev, keycloak, db-gateway) от `up-prod`;
- non-root, read_only, cap_drop, слоистый jar, генерация keystore на старте;
- e2e как bash-сценарии на живом стеке + k6 с порогами;
- гейты стиля на `validate` (spotless/checkstyle) — ловят до тестов.

---

# Часть III. Скорректированный план развития инфраструктуры

Конкретизация плана из части II под целевой контур: **один VPS, Nginx на хосте (TLS/LE), интеграционная ветка `main`**. Эта часть — рабочий план; пункты §12 остаются справочными практиками, здесь — принятые решения.

---

## 16. Приведение в порядок Git, сборки и профилей

### 16.1 Исправление дефолтной ветки (prod → main)

- Слить актуальные изменения из `prod` в `main`.
- В `.github/workflows/ci.yml` оставить триггеры `push` и `pull_request` **только на `main`**.
- Сделать `main` дефолтной веткой репозитория (после слияния `prod` → `main` и проверки CI).

Закрывает расхождение из §14.1: сейчас CI смотрит на `main`, а дефолтная ветка — `prod`.

### 16.2 Фиксация Maven Wrapper

- Удалить `mvnw`, `mvnw.cmd` и `.mvn/` из `.gitignore`.
- Сгенерировать и закоммитить wrapper (`./mvnw wrapper:wrapper`).
- Гарантия одинаковой версии Maven в CI и на всех хостах разработчиков (закрывает §11.2).

### 16.3 Гармонизация профилей (Spring и Docker Compose)

**Профили Spring Boot:**

| Профиль | Роль |
|---|---|
| `dev` | Локальная разработка: подробные логи, внутренний JWT по умолчанию, возможность сброса лимитов |
| `prod` | Боевой режим: JSON-логи, строгий `ProdPreflightValidator` |
| `keycloak` | Аддитивный профиль-миксин поверх основного: `dev,keycloak` или `prod,keycloak` |

Практический выигрыш миксина на VPS: если перестанет хватать RAM на отдельный Keycloak — в `.env` на сервере меняется `SPRING_PROFILES_ACTIVE=prod` (без `keycloak`) и перезапускается контейнер: приложение мгновенно переходит на лёгкий встроенный JWT (http-only cookies) **без пересборки кода**.

**Профили Docker Compose (без изменений концепции):**

| Профиль | Сервисы |
|---|---|
| `infra` | postgres, minio — базовое хранилище |
| `dev` | db-gateway, smtp4dev, локальный keycloak — обвязка разработки |
| `app` | само приложение |
| `monitoring`, `load` | по требованию |

---

## 17. Быстрый dev-контур (запуск с хоста за секунды)

На хосте разработчика стоит JDK 26, а проект требует Java 21 — запуск на хосте делается с явным указанием пути к JDK 21 (через `JAVA_HOME`/`JAVA21_HOME` внутри Makefile). Повышение `java.version` проекта до 26 рассматривается отдельно (не блокер плана).

### 17.1 Автогенерация секретов при первом старте

Доработать `scripts/docker/sync-env.sh` (`make env`): если секреты в `.env` пустые (`JWT_SECRET`, `DB_PASSWORD`, `MINIO_ROOT_PASSWORD`, `CAPTCHA_HMAC_SECRET`, `METRICS_PASSWORD`) — скрипт автоматически заполняет их через `openssl rand -hex 32`. Закрывает §12.0.5 (bootstrap в одну команду).

### 17.2 Разделение запуска в Makefile

| Цель | Что делает |
|---|---|
| `make up-infra` | Поднимает в Docker только профили `infra`+`dev` (postgres, minio, db-gateway, smtp4dev, keycloak). Контейнер `app` **не запускается** |
| `make run-local` | Запускает приложение **на хосте** (`./mvnw spring-boot:run`), подгружая `infrastructure/.env` и переопределяя адреса сервисов на `127.0.0.1` (уже проброшены db-gateway: 5432, 9000, 8180) |
| `make up` | Без изменений — всё в контейнерах для `e2e-all` и финальной проверки перед коммитом |

Inner loop: правка Java → `run-local` перезапускается за секунды (закрывает §14.4).

### 17.3 Чистая БД

- При старте `make run-local` или `make up` Liquibase выполняет **только** контекст `schema` (пустая структура таблиц).
- Тестовые данные наливаются исключительно вручную: `make db-seed` (контекст `seed`).

Сохраняется уже принятое в проекте разделение — просто закрепляется как правило.

---

## 18. Архитектура prod на одном VPS: Nginx + loopback

Держать TLS-терминацию и генерацию PKCS12 keystore внутри контейнера Spring Boot в проде за Nginx не нужно — это лишняя нагрузка и усложнение ротации сертификатов Let's Encrypt.

### 18.1 Reverse Proxy (Nginx на хосте)

- На VPS: штатный системный Nginx + Certbot (Let's Encrypt) + базовый файрвол (открыты только 22, 80, 443).
- В compose порт приложения биндится **строго на loopback хоста**: `127.0.0.1:8080:8080` (или `127.0.0.1:8443:8443`, если оставить внутренний TLS для единообразия с dev). Снаружи в обход Nginx к контейнеру доступа нет.
- В `.env` на сервере: `TRUST_PROXY_HEADERS=true` — механизм уже заложен (`ClientIpResolver`), Spring корректно читает `X-Forwarded-For`/`X-Forwarded-Proto` от Nginx для рейт-лимитов и кук.
- Если внутренний TLS в проде убирается — проверку самоподписанного keystore в `ProdPreflightValidator` сделать условной (проверять только при `server.ssl.enabled=true`): за SSL отвечает Nginx хоста.

### 18.2 Отказ от сборки на сервере (`docker-compose.prod.yaml`)

- Создать `infrastructure/docker-compose.prod.yaml`, переопределяющий сервис `app`: убрать секцию `build`, задать `image: ghcr.io/<аккаунт>/realhelpdesk:${IMAGE_TAG:-latest}` и `pull_policy: always`.
- `make up-prod` выполняет:

```bash
docker compose -f infrastructure/docker-compose.yaml \
  -f infrastructure/docker-compose.prod.yaml \
  --profile infra --profile app up -d --no-build
```

Закрывает §14.2 (сборка на сервере) — deploy только `pull` immutable-образа.

---

## 19. Тестирование и CI/CD без лишней бюрократии

### 19.1 Точечный Testcontainers

- Unit-тесты (`make test`) остаются на **H2** — мгновенно, без Docker.
- Интеграционные (`make verify`, `*IT.java`) переводятся с H2 на **Testcontainers (PostgreSQL 18)** с единым статическим контейнером на весь прогон: проверка миграций Liquibase и SQL на реальном движке (закрывает §14.5).

### 19.2 Сброс Rate-Limit в E2E

Чтобы не делать `make restart` перед каждым `make e2e-all`: внутренний эндпоинт (например, `POST /actuator/ratelimit-reset` или `/api/v1/dev/reset-limits` под `@Profile("dev")`), который вызывается первой строчкой e2e-скриптов и очищает in-memory бакеты.

### 19.3 Двухшаговый Pipeline в GitHub Actions

**Шаг 1 — PR и push в `main`:**

1. `spotless:check` + `checkstyle:check`;
2. `make verify` (Unit + IT на Testcontainers);
3. Сборка Docker-образа → trivy / gitleaks;
4. При пуше в `main` дополнительно: `e2e-all` и пуш образа в **ghcr.io** с тегами `:sha-<git_sha>` и `:latest`.

**Шаг 2 — деплой на VPS (по тегу `v*` или `workflow_dispatch`):**

1. GitHub Actions подключается по SSH к VPS;
2. Записывает новый `IMAGE_TAG` в серверный `.env`, делает `docker compose pull app` и `make up-prod`;
3. Ждёт до 90 секунд `200 OK` от `http://127.0.0.1:8081/actuator/health`;
4. Если healthcheck не прошёл — автоматически возвращает предыдущий `IMAGE_TAG` и перезапускает контейнер (**авто-откат**).

---

## 20. Система бэкапов для будущего prod (Makefile + cron)

PostgreSQL 18 и MinIO живут в изолированной Docker-сети `data` — бэкапы штатными утилитами во временных/exec-контейнерах, управляемые через Makefile.

### 20.1 `scripts/docker/backup.sh` и цель `make backup`

- **База:** `docker compose exec -T postgres pg_dump -U $DB_USER -Fc $DB_NAME > backups/db_$(date +%F_%H-%M).dump` — формат `-Fc` (custom) сжат, восстановление одним потоком через `pg_restore`.
- **Файлы (MinIO):** контейнер `minio/mc` в сети `data` зеркалит бакет (`mc mirror`) в `backups/minio_$(date +%F)/` на хосте.
- **Ротация:** автоматическое удаление архифактов старше N дней (`find ./backups -mtime +14 -delete`) — не забить диск VPS.

### 20.2 Восстановление

`make restore BACKUP_FILE=...` — останавливает `app` (`docker compose stop app`), накатывает дамп в postgres, возвращает app в строй.

### 20.3 Автоматизация на сервере

Одна строка в системном crontab VPS, например:

```
0 3 * * * cd /opt/realhelpdesk && make backup
```

Папку `backups/` при желании синхронизировать во внешнее S3 через rclone.

---

## 21. Сводная таблица: с чего начать по шагам

| Очередность | Задача | Затрагиваемые файлы |
|---|---|---|
| **Шаг 1** | Слить `prod` в `main`, сделать `main` дефолтной, добавить mvnw | `.gitignore`, `.github/workflows/ci.yml`, файлы Maven Wrapper |
| **Шаг 2** | Автогенерация секретов при `make env` и унификация переменных БД/MinIO | `scripts/docker/sync-env.sh`, `infrastructure/.env.example` |
| **Шаг 3** | Быстрый запуск на хосте (`make up-infra`, `make run-local`) + сброс rate-limit в dev | `Makefile`, dev-контроллер/фильтр сброса лимитов, `scripts/e2e-*.sh` |
| **Шаг 4** | Подключение Testcontainers (PostgreSQL 18) для `*IT.java` | `pom.xml`, базовый класс интеграционных тестов |
| **Шаг 5** | Конфиг prod без сборки на сервере + скрипты бэкапа (`make backup`) | `infrastructure/docker-compose.prod.yaml`, `scripts/docker/backup.sh`, `Makefile` |
| **Шаг 6** | Пуш образа в ghcr.io и job деплоя по SSH с проверкой `/actuator/health` | `.github/workflows/ci.yml`, `deploy.yml` |

---

*Документ подготовлен как срез текущего устройства инфраструктуры (часть I), общий план улучшений dev/prod/CI (часть II) и скорректированный план под один VPS, Nginx и ветку `main` (часть III). Все факты сверены с исходниками в ветке `infra/analysis-18`.*

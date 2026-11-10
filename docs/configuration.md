# Конфигурация

Приложение хранит разумные дефолты в `application.properties` в виде
`${VAR:default}`: несекретные настройки (пороги рейт-лимитов, размеры батчей
очистки токенов, таймауты SMTP, капча, ключи наблюдаемости) уже имеют значения
в коде. Без дефолта остались только обязательные переменные: секреты
(`JWT_SECRET`, `DB_PASSWORD`, `CAPTCHA_HMAC_SECRET`,
`MINIO_ROOT_USER/PASSWORD`, `METRICS_PASSWORD`, `MAIL_PASSWORD`) и специфичные
для среды хосты/URL (`MAIL_HOST`, `CORS_ALLOWED_ORIGINS`, `DB_URL`,
`MINIO_ENDPOINT`, `JWT_ISSUER/AUDIENCE`, `FRONT_DOMAIN`, `MAIL_FROM/NOTIFY`,
`STATIC_BASE_URL`). Если такая переменная не задана, приложение не стартует и
называет причину (`Could not resolve placeholder '<VAR>'`).

## Где живут значения

| Файл | Что это | Когда менять |
|---|---|---|
| `src/main/resources/application*.properties` | Схема `${VAR}` или `${VAR:default}`: разумные дефолты несекретных ключей живут здесь | Изменить дефолт для всех окружений |
| `infrastructure/.env.dev.example` | Шаблон локальной разработки: секреты (пустые), специфичные для среды хосты (dev-домены, smtp4dev, адреса compose-сети), Keycloak, JVM-флаги. `make env` копирует его в `.env` | Новый обязательный ключ, новый dev-дефолт |
| `infrastructure/.env` | Локальный файл, в git не попадает. `make env` создаёт его из dev-шаблона и генерирует пустые секреты случайными значениями. Существующий файл скрипт не меняет. Его читают compose (интерполяция `${VAR}`) и хостовое приложение (`scripts/dev/load-env.sh`) | Локальные значения и секреты |

Секреты: `JWT_SECRET`, `CAPTCHA_HMAC_SECRET`, `DB_PASSWORD`,
`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `METRICS_PASSWORD`,
`MAIL_PASSWORD` (только для внешнего SMTP),
`KEYCLOAK_ADMIN_PASSWORD` (если стенд Keycloak доступен не только вам),
`KEYCLOAK_DB_PASSWORD` (пароль отдельного кластера PostgreSQL для Keycloak,
сервис `keycloak-postgres`).

Смена секрета у уже поднятого стека: `DB_PASSWORD` фиксируется при первой
инициализации тома PostgreSQL, поэтому его правка в `.env` рассогласует
приложение и БД. То же касается `KEYCLOAK_DB_PASSWORD` — он фиксируется
томом `keycloak_postgres_data`. Меняйте вместе с пересозданием данных:
`make dev-down--del-volumes` + повторный подъём стека.

## Как поменять настройку

1. Найдите `${VAR}` или `${VAR:default}` в `src/main/resources/application.properties`
   (или `application-domains.properties`).
2. Задайте значение: в `infrastructure/.env` — локально; в
   `application.properties` — дефолт для всех окружений. Секреты при
   необходимости объявляются и в compose-окружении контейнеров
   (`services.postgres/environment`, `services.minio/environment`) — там они
   подставляются как `${VAR:?}`: пустой или отсутствующий секрет валит старт
   инфраструктуры.
3. Примените: приложение на хосте перезапускается вручную (`make run-local` /
   `make run-local-keycloak`) — `.env` перечитывается при старте; контейнеры
   инфраструктуры пересоздаются `make dev-run`.

Обязательная переменная (без дефолта) объявлена в env-шаблоне
`infrastructure/.env.dev.example`. Несекретный ключ с `${VAR:default}`
объявлять не нужно — значение возьмётся из схемы. Парность сверяет
`ApplicationConfigSchemaTest` (`make test`).

## Compose-файлы

Ядро (`infrastructure/docker-compose.yaml`) делится профилями `infra` /
`local-tools`; наблюдаемость и нагрузочное тестирование вынесены в отдельные
файлы и подключаются цепочкой `-f`, когда они нужны. Приложения в compose нет:
процесс Java живёт на хосте.

| Файл | Назначение |
|---|---|
| `infrastructure/docker-compose.yaml` | Ядро: postgres, minio, локальные инструменты (`smtp4dev`, Keycloak) |
| `infrastructure/docker-compose.obs.yaml` | Наблюдаемость: mimir, tempo, otel-collector, alloy (профиль `monitoring`). Подключает `make dev-run` |
| `infrastructure/docker-compose.load.yaml` | Нагрузочное тестирование: k6 (профиль `load`). Подключают `make load-full` / `make load-smoke` |
| `infrastructure/docker-compose.override.yaml` | Локальная разработка: порты postgres/minio на `127.0.0.1` + сеть `backend` для приложения на хосте. Подключают только локальные цели make |
| `infrastructure/docker-compose.host.yaml` | Одностраничная сборка через top-level `include:` для spring-boot-docker-compose (модуль Boot принимает только один файл) |

Порты БД/MinIO в базовом файле не публикуются: наружу на хост не должно
выходить ничего лишнего, а локальному хостовому приложению доступ дают
оверлейные публикации на `127.0.0.1` (плюс подключение сервисов к сети
`backend` — на internal-сети Docker порты не публикует).

Цепочки `-f` инкапсулированы в Makefile (`COMPOSE`, `COMPOSE_OBS`,
`COMPOSE_LOAD`) — запуск идёт через `make dev-run` (инфраструктура +
наблюдаемость, приложение на хосте) / `make load-full` / `make load-smoke`.

## Клиентский IP

Логи (MDC `clientIp`) и ключ рейт-лимита получают адрес посетителя из одного
резолвера (`ClientIpResolver`). Настройки — в `infrastructure/.env`:

| Переменная | Назначение |
|---|---|
| `TRUST_PROXY_HEADERS` | `false` (по умолчанию) — адрес сокета запроса; `true` — из заголовков прокси. Включать только за прокси: иначе клиент подставит чужой IP в логи и в счётчик лимита |
| `CLIENT_IP_HEADERS` | Заголовки через запятую по приоритету, берётся первый пригодный: `X-Forwarded-For`, `X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`, `X-Client-IP`, `Proxy-Client-IP`/`WL-Proxy-Client-IP`, `Forwarded` (RFC 7239) |
| `CLIENT_IP_FORWARDED_INDEX` | Какой элемент списка брать: `0` — первый слева (адрес клиента), отрицательный — справа (`-1` — ближайший прокси). За цепочкой из нескольких прокси ставьте `-1` |

## Профили Spring

Профиль задаётся переменной `SPRING_PROFILES_ACTIVE`. Для приложения на
хосте её формирует `scripts/dev/run-local.sh` из `RUN_PROFILES` (добавляя
`host`), поэтому контур переключается целью запуска:

```bash
make run-local          # SPRING_PROFILES_ACTIVE=dev,host          (внутренний JWT-контур)
make run-local-keycloak # SPRING_PROFILES_ACTIVE=dev,keycloak,host (токены выпускает Keycloak)
```

В `infrastructure/.env` переменной `SPRING_PROFILES_ACTIVE` быть не должно:
`run-local.sh` экспортирует её ДО загрузки `.env`, и значение из файла
перезаписало бы контур, выбранный make-целью (инвариант проверяет
`ApplicationConfigSchemaTest`).

| Профиль | Файл | Что включает |
|---|---|---|
| `dev` | `application-dev.properties` | Локальная отладка: DEBUG по приложению, SQL-логи Hibernate без значений параметров |
| `host` | `application-host.properties` | Запуск на хосте поверх `dev` (IDEA/CLI): loopback-адреса БД/MinIO/почты, spring-boot-docker-compose |
| `keycloak` | `application-keycloak.properties` | Внешний выпуск токенов: OAuth2 Resource Server, JWK и issuer, синхронизация ролей, автосоздание пользователей |

Профиль `keycloak` — надстройка над базовым `dev`, не самостоятельный профиль.
Без него работает внутренний JWT-контур приложения
(см. [`authentication.md`](authentication.md)).

Профиль `host` — надстройка над `dev`, не самостоятельный профиль.
Всегда используется как `dev,host` (IDEA, `make run-local`,
`scripts/dev/run-local.sh`); в тестах не активен.

Зачем нужен: при хостовом запуске приложение работает вне Docker, но
инфраструктура — в контейнерах. По умолчанию `infrastructure/.env`
содержит compose-имена (`jdbc:postgresql://postgres:5432`,
`minio.endpoint=http://minio:9000`, `MAIL_HOST=smtp`) — из хоста они
недоступны. Профиль `host` переопределяет адреса на `127.0.0.1` с
опубликованными портами:

| Сервис | В `.env` (compose-сеть) | В `host` (loopback) | Порт |
|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://postgres:5432/desk` | `jdbc:postgresql://127.0.0.1:5432/desk` | `5432` |
| MinIO | `http://minio:9000` | `http://127.0.0.1:9000` | `9000` |
| SMTP (smtp4dev) | `smtp` | `127.0.0.1` | `25` |

Keycloak: `KC_JWK_SET_URI` из `.env` указывает на `keycloak:8080` —
переопределить его в `application-host.properties` нельзя, так как
переменная окружения хоста имеет приоритет над profile-файлами. Для
запуска Keycloak с хоста задайте `KC_JWK_SET_URI=http://127.0.0.1:8180/...`
в env-переменной запуска (IDEA или `run-local.sh`).

spring-boot-docker-compose: при старте с профилем `host` модуль
автоматически поднимает стек из `docker-compose.host.yaml`
(profiles `infra,local-tools`, `lifecycle-management=start-only`).
Стек не останавливается вместе с приложением — им дальше пользуются
`make` и e2e-сценарии. В тестах профиль `host` не активен.

### Запуск из IDEA

1. `make dev-run` — поднять стек (БД, MinIO, Keycloak, наблюдаемость) в фоне;
   либо не поднимать ничего: профиль `host` сделает это сам через
   spring-boot-docker-compose (только infra+local-tools). Порт 8080
   контейнеры не занимают — приложение всегда на хосте.
2. Открыть проект в IntelliJ IDEA (2025.2+): конфигурация
   `RealHelpDeskApplication` (`.run/RealHelpDeskApplication.run.xml`)
   настроена — запуск через Maven (`spring-boot:run`, профили `dev,host`),
   env — снапшот значений из `infrastructure/.env` (правки `.env`
   не подхватываются, обновляйте env-карту run-конфига) с инлайн-переменными
   `MINIO_ENDPOINT=http://127.0.0.1:9000` и
   `OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces`.
3. Debug: приложение стартует за несколько секунд, подключаясь к
   `127.0.0.1:5432`/`9000`/`25`.

Для IDEA старше 2025.2 — плагин EnvFile и путь к `infrastructure/.env`
на его вкладке.

## Почта

Приложение отправляет письма только во внешний SMTP; встроенного SMTP нет.
В dev-среде поднимается smtp4dev — тестовый SMTP-сервис (контейнер `smtp`,
UI на `http://localhost:3000`), профиль compose `local-tools`.

smtp4dev нужен dev и e2e: регистрация, подтверждение email и восстановление
пароля отправляют письма с кодами, и сценарии (`scripts/e2e-jwt-flow.sh`,
`scripts/e2e-portal-transfer.sh`) забирают коды через его REST API.
В интеграционных тестах (`mvn verify`) почту принимает GreenMail.

Настройки — `spring.mail.*` в `infrastructure/.env`:

| Переменная | Назначение |
|---|---|
| `MAIL_PROTOCOL` / `MAIL_HOST` / `MAIL_PORT` | Транспорт и адрес SMTP-сервера |
| `MAIL_USER` / `MAIL_PASSWORD` | Учётная запись SMTP; локальному smtp4dev пароль не нужен (`SMTP_AUTH=false`) |
| `SMTP_AUTH` / `SMTP_STARTTLS` | Аутентификация и STARTTLS; `SMTP_AUTH=true` требует `MAIL_USER` |
| `MAIL_FROM` | Отправитель писем |
| `MAIL_NOTIFY` | Адрес для уведомлений о заявках |
| `APP_PROJECT_NAME` | Название проекта в письмах |
| `MAIL_SMTP_CONNECTION_TIMEOUT` / `_READ_TIMEOUT` / `_WRITE_TIMEOUT` | Таймауты SMTP в мс: соединение, чтение, запись |

Пустой `MAIL_HOST` валит старт (переменная обязательна), адрес локального
SMTP (`localhost`, `127.0.0.1`, контейнер `smtp`) — штатная конфигурация dev и
тестов. Доступность
сервера отдаёт health-группа `smtp` (`/actuator/health/mail`, рукопожатие без
письма, кэш 5 с); она вне readiness — недоступная почта не останавливает
приём заявок.

## Миграции БД

Структуру создаёт и обновляет Liquibase при старте; Hibernate только сверяет
сущности со схемой (`spring.jpa.hibernate.ddl-auto=validate`). Миграции —
`src/main/resources/db/changelog` (baseline `001-baseline-schema` + новые
changeset'ы). `make dev-down--del-volumes` удаляет том PostgreSQL.

### Изменение модели данных

При каждом изменении схемы (новая таблица, колонка, индекс, constraint)
создаётся отдельный файл миграции — это гарантирует идемпотентность,
возможность отката и чёткий audit-лог.

Порядок действий:

1. **Создать changeset.** Файл `db/changelog/changes/NNN-<slug>.xml`
   (число больше любого существующего, `slug` — краткое описание на
   английском, например `add-attachment-size-column`).
2. **SQL вне XML.** Если изменения сложное — DDL с несколькими шагами,
   conditional logic, или просто чтобы не раздувать XML — создайте
   `db/changelog/sql/NNN-<slug>.sql` и подключите через `<sqlFile>` в
   changeset. Путь указывается от корня classpath
   (`db/changelog/sql/NNN-<slug>.sql`).
3. **Подключить в мастер-челодже.** Добавьте `<include>` в
   `db.changelog-master.xml` **перед** сид-файлом (`seed/100-...`).
   Нумерация не меняется после применения миграций — если changeset уже
   накатан на базу, создавайте новый с большим номером, а не правьте
   существующий (Liquibase считает контрольную сумму и упадёт).
4. **Синхронизировать сущности.** Измените JPA-модель (`@Column`,
   `@Table`, `@Index` и т.д.) — Hibernate валидирует модель против схемы
   при старте (`validate`). Если `validate` падает — схема и сущности
   рассинхронизированы.
5. **Проверить локально.** `make dev-run` + запуск приложения из IDE — Liquibase
   применит миграцию автоматически. Убедитесь, что приложение стартует без ошибок.
6. **Тесты.** `LiquibaseChangelogGuardTest` следит, чтобы в `changes/`
   не было DML (INSERT/UPDATE/DELETE) — только DDL. Данные, если нужны,
   кладутся в `seed/` с `context="seed"`.
7. **Продакшен.** При деплое миграции применяются автоматически при
   первом старте нового репликата. Liquibase хранит историю в таблице
   `databasechangelog` — повторный запуск одного и того же changeset
   безопасен (он уже в истории).

**Важные правила:**

* Один changeset — одно логическое изменение. Не смешивайте в одном файле
  добавление колонки и пересоздание индекса.
* Изменения enum-значений в колонках с CHECK-ограничением требуют
  отдельного changeset'а с пересозданием ограничения (ALTER TABLE DROP
  CONSTRAINT → ALTER TYPE → ALTER TABLE ADD CONSTRAINT).
* Удаление колонки — полный флоу: отключение в сущности → migration →
  проверка, что все места кода очищены → удаление. Не удаляйте колонку,
  пока на ней есть активные зависимости.
* Сиквенты и генерация ID — Liquibase управляет ими через
  `databasechangeloglock`. Конкурентные деплои безопасны.

Контекст задаёт `DB_MIGRATIONS_CONTEXTS` (дефолт `schema`): база поднимается пустой,
структура применяется всегда. Фикстуры лежат в `db/changelog/seed`
(`context="seed"`) и попадают в БД только по команде:

```bash
DB_MIGRATIONS_CONTEXTS=schema,seed  # демо-пользователи, портал, заявка с диалогом
make dev-down--del-volumes          # обратно: чистая схема
```

INSERT'ы идемпотентны, повторный сид безопасен. E2E и нагрузка сид не
используют — создают данные через HTTP API.

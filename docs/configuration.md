# Конфигурация

Приложение хранит разумные дефолты в `application.properties` в виде
`${VAR:default}`: несекретные настройки (пороги рейт-лимитов, размеры батчей
очистки токенов, таймауты SMTP, капча, ключи наблюдаемости) уже имеют значения
в коде. Без дефолта остались только обязательные переменные: секреты
(`JWT_SECRET`, `DB_PASSWORD`, `CAPTCHA_HMAC_SECRET`, `KEY_STORE_PASS`,
`MINIO_ROOT_USER/PASSWORD`, `METRICS_PASSWORD`, `MAIL_PASSWORD`) и специфичные
для среды хосты/URL (`MAIL_HOST`, `CORS_ALLOWED_ORIGINS`, `DB_URL`,
`MINIO_ENDPOINT`, `JWT_ISSUER/AUDIENCE`, `FRONT_DOMAIN`, `MAIL_FROM/NOTIFY`,
`STATIC_BASE_URL`). Если такая переменная не задана, приложение не стартует и
называет причину (`Could not resolve placeholder '<VAR>'`).

## Где живут значения

| Файл | Что это | Когда менять |
|---|---|---|
| `src/main/resources/application*.properties` | Схема `${VAR}` или `${VAR:default}`: разумные дефолты несекретных ключей живут здесь | Изменить дефолт для всех окружений |
| `infrastructure/.env.dev.example` | Шаблон для локальной разработки: секреты (пустые), специфичные для среды хосты (dev-домены, smtp4dev, MinIO в compose-сети), Keycloak, JVM-флаги. `make env` копирует его в `.env` | Новый обязательный ключ, новый dev-дефолт |
| `infrastructure/.env.prod.example` | Шаблон для рабочего контура: секреты (пустые), prod-домены, внешний SMTP, JVM-флаги. На VPS копируется вручную | Новый обязательный ключ для прода |
| `infrastructure/.env` | Рабочий файл, в git не попадает. `make env` создаёт его из dev-шаблона и генерирует пустые секреты случайными значениями. Существующий файл скрипт не меняет. Его читают compose (интерполяция `${VAR}`) и приложение (`env_file`) | Локальные значения и секреты |
| `services.app.environment` в `infrastructure/docker-compose.yaml` | Мост compose → приложение: секреты подставляются как `${VAR:?}` — пустой или отсутствующий секрет валит запуск. Здесь же `SPRING_PROFILES_ACTIVE` — переопределяется инлайном (`make profile-local`, `up-load`, e2e-сценарии) | Новая секретная переменная |

Секреты: `JWT_SECRET`, `CAPTCHA_HMAC_SECRET`, `DB_PASSWORD`,
`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `METRICS_PASSWORD`,
`KEY_STORE_PASS`, `MAIL_PASSWORD` (только для внешнего SMTP),
`KEYCLOAK_ADMIN_PASSWORD` (если стенд Keycloak доступен не только вам),
`KEYCLOAK_DB_PASSWORD` (пароль отдельного кластера PostgreSQL для Keycloak,
сервис `keycloak-postgres`).

Смена секрета у уже поднятого стека: `DB_PASSWORD` фиксируется при первой
инициализации тома PostgreSQL, поэтому его правка в `.env` рассогласует
приложение и БД. То же касается `KEYCLOAK_DB_PASSWORD` — он фиксируется
томом `keycloak_postgres_data`. Меняйте вместе с пересозданием данных:
`make down-volumes && make up`.

## Как поменять настройку

1. Найдите `${VAR}` или `${VAR:default}` в `src/main/resources/application.properties`
   (или `application-domains.properties`).
2. Задайте значение: в `infrastructure/.env` — локально (или `.env` на VPS,
   созданный из `.env.prod.example`); в `application.properties` — дефолт для
   всех окружений; в `services.app.environment` compose — секрет.
3. Примените: `make apply-config` — пересоздаёт контейнер `app` без
   пересборки образа (для `SPRING_PROFILES_ACTIVE` есть `make profile-local` /
   `profile-keycloak`). Если `.env` заполнен вручную — достаточно
   `make restart`.

Обязательная переменная (без дефолта) объявлена ровно в одном месте —
`services.app.environment` compose (секреты) или одном из env-шаблонов.
Несекретный ключ с `${VAR:default}` объявлять не нужно — значение возьмётся
из схемы. Парность сверяет `ApplicationConfigSchemaTest` (`make test`).

## Compose-файлы

Ядро (`infrastructure/docker-compose.yaml`) делится профилями `infra` /
`local-tools` / `app`; наблюдаемость и нагрузочное тестирование вынесены в
отдельные файлы и подключаются цепочкой `-f`, когда они нужны:

| Файл | Назначение |
|---|---|
| `infrastructure/docker-compose.yaml` | Ядро: приложение, postgres, minio, локальные инструменты (`smtp4dev`, Keycloak), `services.app.environment` (секреты через `${VAR:?}`) |
| `infrastructure/docker-compose.obs.yaml` | Наблюдаемость: mimir, tempo, loki, otel-collector, alloy (профиль `monitoring`). Подключает `make obs-up` |
| `infrastructure/docker-compose.load.yaml` | Нагрузочное тестирование: k6 (профиль `load`). Подключает `make load` |
| `infrastructure/docker-compose.override.yaml` | Локальная разработка: порты postgres/minio на `127.0.0.1` + сеть `backend` для приложения на хосте. Подключают только локальные цели make |
| `infrastructure/docker-compose.prod.yaml` | Рабочий контур (`make up-prod`): pull из ghcr.io, дефолт профиля `prod` (`${SPRING_PROFILES_ACTIVE:-prod}`), без портов БД наружу; override не подключён |
| `infrastructure/docker-compose.host.yaml` | Одностраничная сборка через top-level `include:` для spring-boot-docker-compose (модуль Boot принимает только один файл) |

Почему порты нельзя переключить профилем: профиль не может сказать «в dev
публикуй 5432 на loopback, в prod не публикуй» — пришлось бы дублировать
сервисы, ломая `depends_on`, hostname и конфиг приложения. Прод собирается
как `-f base -f prod`, поэтому случайный `--profile` не может опубликовать
порты БД наружу.

Цепочки `-f` инкапсулированы в Makefile (`COMPOSE`, `COMPOSE_OBS`,
`COMPOSE_LOAD`, `COMPOSE_PROD`) — запуск идёт через `make up-deps` / `make up`
/ `make obs-up` / `make load` / `make up-prod`.

## Клиентский IP

Логи (MDC `clientIp`) и ключ рейт-лимита получают адрес посетителя из одного
резолвера (`ClientIpResolver`). Настройки — в `infrastructure/.env`:

| Переменная | Назначение |
|---|---|
| `TRUST_PROXY_HEADERS` | `false` (по умолчанию) — адрес сокета запроса; `true` — из заголовков прокси. Включать только за прокси: иначе клиент подставит чужой IP в логи и в счётчик лимита |
| `CLIENT_IP_HEADERS` | Заголовки через запятую по приоритету, берётся первый пригодный: `X-Forwarded-For`, `X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`, `X-Client-IP`, `Proxy-Client-IP`/`WL-Proxy-Client-IP`, `Forwarded` (RFC 7239) |
| `CLIENT_IP_FORWARDED_INDEX` | Какой элемент списка брать: `0` — первый слева (адрес клиента), отрицательный — справа (`-1` — ближайший прокси). За цепочкой из нескольких прокси ставьте `-1` |

## Профили Spring

Профиль задаётся переменной `SPRING_PROFILES_ACTIVE`. Она объявлена в
`services.app.environment` compose-файла с дефолтом `${SPRING_PROFILES_ACTIVE:-dev,keycloak}`,
поэтому переключается инлайном — переменная окружения хоста имеет приоритет
над `infrastructure/.env`, и на диск ничего не пишется:

```bash
make profile-local     # SPRING_PROFILES_ACTIVE=dev          (внутренний JWT-контур)
make profile-keycloak  # SPRING_PROFILES_ACTIVE=dev,keycloak (токены выпускает Keycloak)
```

Compose сравнивает конфигурацию контейнера: при смене значения `app`
пересоздаётся сам, при том же — команда ничего не делает. Постоянное значение,
если нужно, можно задать в `infrastructure/.env` (переменная окружения хоста
всё равно перекрывает файл). В рабочем контуре дефолт другой:
`docker-compose.prod.yaml` подставляет `prod` (`${SPRING_PROFILES_ACTIVE:-prod}`) —
чтобы забытый ключ не поднял dev-режим на сервере. Для запуска на хосте
профиль задаёт `.run/`-конфигурация IDEA или `make run-local` (`dev,host`).

| Профиль | Файл | Что включает |
|---|---|---|
| `dev` | `application-dev.properties` | Локальная отладка: DEBUG по приложению, SQL-логи Hibernate без значений параметров |
| `prod` | `application-prod.properties` | Рабочий контур: INFO/WARN, трассировка и SQL выключены, инварианты рабочего контура |
| `host` | `application-host.properties` | Запуск на хосте поверх `dev` (IDEA/CLI): loopback-адреса БД/MinIO/почты, spring-boot-docker-compose. Контейнерное приложение профиль не видит |
| `keycloak` | `application-keycloak.properties` | Внешний выпуск токенов: OAuth2 Resource Server, JWK и issuer, синхронизация ролей, автосоздание пользователей |

Профиль `keycloak` — надстройка над базовым `dev` или `prod`, не
самостоятельный профиль. Без него работает внутренний JWT-контур приложения
(см. [`authentication.md`](authentication.md)).

`ProdPreflightValidator` останавливает старт рабочего контура с отладочной
конфигурацией: выключенная капча, localhost в CORS, самоподписанный keystore,
почта на локальном SMTP.

### Запуск из IDEA

1. `make up-deps` — поднять БД, MinIO, smtp4dev и Keycloak (или не поднимать:
   профиль `host` сделает это сам через spring-boot-docker-compose).
2. Открыть проект в IntelliJ IDEA (2025.2+): конфигурация
   `RealHelpDeskApplication` (`.run/RealHelpDeskApplication.run.xml`)
   настроена — профили `dev,host`, путь к `infrastructure/.env`,
   инлайн-переменные `SSL_ENABLED=false`,
   `MINIO_ENDPOINT=http://127.0.0.1:9000` и дефолты compose-интерполяции
   `OTLP_TRACING_ENDPOINT` / `TRUST_PROXY_HEADERS` / `CLIENT_IP_FORWARDED_INDEX`.
3. Debug: приложение стартует за 2–3 секунды, подключаясь к
   `127.0.0.1:5432`/`9000`/`25`.

Для IDEA старше 2025.2 — плагин EnvFile и путь к `infrastructure/.env`
на его вкладке.

## Почта

Приложение отправляет письма только во внешний SMTP; встроенного SMTP нет.
В dev-среде поднимается smtp4dev — тестовый SMTP-сервис (контейнер `smtp`,
UI на `http://localhost:3000`), профиль compose `local-tools`; в рабочем
контуре его нет.

smtp4dev нужен dev и e2e: регистрация, подтверждение email и восстановление
пароля отправляют письма с кодами, и сценарии (`scripts/e2e-jwt-flow.sh`,
`scripts/e2e-portal-transfer.sh`) забирают коды через его REST API.
В интеграционных тестах (`mvn verify`) почту принимает GreenMail.

Настройки — `spring.mail.*` в `infrastructure/.env`:

| Переменная | Назначение |
|---|---|
| `MAIL_PROTOCOL` / `MAIL_HOST` / `MAIL_PORT` | Транспорт и адрес SMTP-сервера |
| `MAIL_USER` / `MAIL_PASSWORD` | Учётная запись SMTP; локальному smtp4dev пароль не нужен (`SMTP_AUTH=false`) |
| `SMTP_AUTH` / `SMTP_STARTTLS` | Аутентификация и STARTTLS; `SMTP_AUTH=true` без `MAIL_USER` — ошибка preflight |
| `MAIL_FROM` | Отправитель писем |
| `MAIL_NOTIFY` | Адрес для уведомлений о заявках |
| `APP_PROJECT_NAME` | Название проекта в письмах |
| `MAIL_SMTP_CONNECTION_TIMEOUT` / `_READ_TIMEOUT` / `_WRITE_TIMEOUT` | Таймауты SMTP в мс: соединение, чтение, запись |

Контроль рабочего контура: preflight не даёт стартовать с пустым `MAIL_HOST`,
адресом локального SMTP (`localhost`, `127.0.0.1`, контейнер `smtp`), портом
вне 1–65535, без `MAIL_FROM`, с `SMTP_AUTH=true` без `MAIL_USER`. Доступность
сервера отдаёт health-группа `smtp` (`/actuator/health/mail`, рукопожатие без
письма, кэш 5 с); она вне readiness — недоступная почта не останавливает
приём заявок.

## Миграции БД

Структуру создаёт и обновляет Liquibase при старте; Hibernate только сверяет
сущности со схемой (`spring.jpa.hibernate.ddl-auto=validate`). Миграции —
`src/main/resources/db/changelog` (baseline `001-baseline-schema` + новые
changeset'ы). `make down-volumes` удаляет том PostgreSQL.

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
   Нумерация не меняется после публикации — если changeset уже применён
   к production, создавайте новый с большим номером, а не правьте
   существующий (Liquibase считает контрольную сумму и упадёт).
4. **Синхронизировать сущности.** Измените JPA-модель (`@Column`,
   `@Table`, `@Index` и т.д.) — Hibernate валидирует модель против схемы
   при старте (`validate`). Если `validate` падает — схема и сущности
   рассинхронизированы.
5. **Проверить локально.** `make up-deps && make up` — Liquibase применит
   миграцию автоматически. Убедитесь, что приложение стартует без ошибок.
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
make db-seed                    # демо-пользователи, портал, заявка с диалогом
make down-volumes && make up    # обратно: чистая схема
```

INSERT'ы идемпотентны, повторный сид безопасен. E2E и нагрузка сид не
используют — создают данные через HTTP API.

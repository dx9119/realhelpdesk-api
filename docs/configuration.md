# Конфигурация

Приложение не хранит значений настроек в коде: каждая настройка — переменная
окружения без дефолта. В `src/main/resources/application.properties` и
`application-domains.properties` записана только схема — строки вида `${VAR}`
без значения. Если переменная не задана, приложение не стартует и называет
причину (`Could not resolve placeholder '<VAR>'`).

## Где живут значения

| Файл | Что это | Когда менять |
|---|---|---|
| `infrastructure/.env.example` | Шаблон из git: несекретные переменные с дефолтами для локальной разработки (рейт-лимиты, капча, почта, MinIO, домены) и пустые строки секретов. Закомментированная секция «Рабочий контур» — значения для продакшена | Общий дефолт для всех разработчиков, новая несекретная переменная |
| `infrastructure/.env` | Рабочий файл, в git не попадает. `make env` создаёт его из шаблона (если файла нет) и генерирует пустые секреты случайными значениями. Существующий файл скрипт не меняет: у несекретных ключей источник один — шаблон, а секреты после генерации ваши. Его читают compose (интерполяция `${VAR}`) и приложение (`env_file`) | Локальные значения и секреты |
| `services.app.environment` в `infrastructure/docker-compose.yaml` | Мост compose → приложение: секреты подставляются как `${VAR:?}` — пустой или отсутствующий секрет валит запуск; здесь же динамические ключи с дефолтами `${VAR:-...}` — профиль, сроки ротации JWT, `OTLP_TRACING_ENDPOINT`, `TRUST_PROXY_HEADERS`, `CLIENT_IP_FORWARDED_INDEX`. Их переопределяют инлайном (`make profile-local`, `up-load`, e2e-сценарии), не трогая файлы | Новая секретная переменная или новый динамический ключ |

Секреты: `JWT_SECRET`, `CAPTCHA_HMAC_SECRET`, `DB_PASSWORD`,
`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `METRICS_PASSWORD`,
`KEY_STORE_PASS`, `MAIL_PASSWORD` (только для внешнего SMTP),
`KEYCLOAK_ADMIN_PASSWORD` (если стенд Keycloak доступен не только вам).

Смена секрета у уже поднятого стека: `DB_PASSWORD` фиксируется при первой
инициализации тома PostgreSQL, поэтому его правка в `.env` рассогласует
приложение и БД. Меняйте вместе с пересозданием данных:
`make down-volumes && make up`.

## Как поменять настройку

1. Найдите `${VAR}` в `src/main/resources/application.properties`
   (или `application-domains.properties`).
2. Задайте значение: в `infrastructure/.env` — локально;
   в `infrastructure/.env.example` — общий дефолт;
   в `services.app.environment` compose — секрет или динамический ключ.
3. Примените: `make apply-config` — пересоздаёт контейнер `app` без
   пересборки образа (для `SPRING_PROFILES_ACTIVE` есть `make profile-local` /
   `profile-keycloak`). Если `.env` заполнен вручную — достаточно
   `make restart`.

Каждая переменная схемы объявлена ровно в одном месте — `.env.example`
(несекретные) или `services.app.environment` compose (секреты и динамические
ключи). Парность сверяет `ApplicationConfigSchemaTest` (`make test`).

## Compose-файлы

Один базовый файл (`infrastructure/docker-compose.yaml`) делится профилями
`infra` / `local-tools` / `app` / `monitoring` / `load` — профили выбирают
*какие сервисы* запускать; различия конфигурации сред живут в отдельных
файлах, потому что профиль не может изменить конфигурацию сервиса:

| Файл | Назначение |
|---|---|
| `infrastructure/docker-compose.yaml` | База: сервисы, профили, `services.app.environment` (секреты через `${VAR:?}`) |
| `infrastructure/docker-compose.override.yaml` | Локальная разработка: порты postgres/minio на `127.0.0.1` + сеть `backend` для приложения на хосте. Подключают только локальные цели make |
| `infrastructure/docker-compose.prod.yaml` | Рабочий контур (`make up-prod`): pull из ghcr.io, дефолт профиля `prod` (`${SPRING_PROFILES_ACTIVE:-prod}`), без портов БД наружу; override не подключён |
| `infrastructure/docker-compose.host.yaml` | Одностраничная сборка через top-level `include:` для spring-boot-docker-compose (модуль Boot принимает только один файл) |

Почему порты нельзя переключить профилем: профиль не может сказать «в dev
публикуй 5432 на loopback, в prod не публикуй» — пришлось бы дублировать
сервисы, ломая `depends_on`, hostname и конфиг приложения. Прод собирается
как `-f base -f prod`, поэтому случайный `--profile` не может опубликовать
порты БД наружу.

Цепочки `-f` инкапсулированы в Makefile (`COMPOSE`, `COMPOSE_PROD`) — запуск
идёт через `make up-deps` / `make up` / `make up-prod`.

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

`changes/` содержит только DDL — тестовых данных в структурных миграциях нет
(охраняет `LiquibaseChangelogGuardTest`). Контекст задаёт
`DB_MIGRATIONS_CONTEXTS` (дефолт `schema`): база поднимается пустой,
структура применяется всегда. Фикстуры лежат в `db/changelog/seed`
(`context="seed"`) и попадают в БД только по команде:

```bash
make db-seed                    # демо-пользователи, портал, заявка с диалогом
make down-volumes && make up    # обратно: чистая схема
```

INSERT'ы идемпотентны, повторный сид безопасен. E2E и нагрузка сид не
используют — создают данные через HTTP API.

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

Базовый стек — Java 25 и Spring Boot 4.1.1 на PostgreSQL 18; инфраструктура
описана в Docker Compose. Ниже — полный список технологий и зависимостей
с версиями и решаемыми задачами. Источник версий для Java-зависимостей —
`pom.xml` (большинство версий задаёт BOM Spring Boot 4.1.1), для сервисов —
`infrastructure/docker-compose.yaml`.

### Язык и платформа

| Технология | Версия | Решаемая задача |
|---|---|---|
| Java | 25 | Язык и целевой уровень компиляции (`release=25`) |
| Maven (Maven Wrapper) | 3.9.11 (3.3.4) | Сборка, тесты, плагины; wrapper фиксирует версию Maven для разработчиков и CI |
| Spring Boot | 4.1.1 | Каркас приложения, авто-конфигурация, management-контур |
| Spring Framework | 7.0.9 | DI-контейнер, WebMVC, транзакции, JDBC |
| Tomcat (embedded) | 11.0.25 | Servlet-контейнер REST API; патч 11.0.24→11.0.25 — требование trivy (CRITICAL/HIGH) |
| Jackson 2 (core / databind / annotations) | 2.21.7 | JSON: сериализация DTO, чтение тел запросов, JWT |
| Jackson 3 (`tools.jackson`) | 3.1.7 | JSON для Actuator и внутренних конвертеров Boot 4 |
| SnakeYAML | 2.6 | YAML-конфигурации (Spring, OTel и др.) |

### Spring Boot starters

| Starter | Версия | Решаемая задача |
|---|---|---|
| spring-boot-starter-web | 4.1.1 | REST-контроллеры, JSON, embedded Tomcat |
| spring-boot-starter-data-jpa | 4.1.1 | JPA-репозитории, транзакции, интеграция с Hibernate |
| spring-boot-starter-security | 4.1.1 | Аутентификация и авторизация, security-фильтры, `@PreAuthorize` |
| spring-boot-starter-oauth2-resource-server | 4.1.1 | Проверка подписи JWT и извлечение ролей (в т.ч. Keycloak / JWK) |
| spring-boot-starter-validation | 4.1.1 | Валидация DTO и параметров (Bean Validation) |
| spring-boot-starter-mail | 4.1.1 | Отправка email (верификация, восстановление пароля, уведомления) |
| spring-boot-starter-liquibase | 4.1.1 | Применение миграций схемы при старте приложения |
| spring-boot-starter-actuator | 4.1.1 | `/actuator/health` (liveness/readiness), `/actuator/prometheus` |
| spring-boot-starter-opentelemetry | 4.1.1 | Распределённая трассировка, экспорт трейсов по OTLP |
| spring-boot-docker-compose | 4.1.1 | Dev: поднимает/подхватывает инфру при запуске с хоста (`optional`, в prod-образ не попадает) |

### Данные

| Технология | Версия | Решаемая задача |
|---|---|---|
| PostgreSQL | 18 | Основная реляционная БД (порталы, заявки, пользователи, refresh-токены) |
| PostgreSQL JDBC Driver | 42.7.13 | JDBC-драйвер БД |
| HikariCP | 7.0.2 | Пул соединений с БД |
| Spring Data JPA | 4.1.1 | Репозитории, критерии поиска, пагинация |
| Hibernate ORM | 7.4.5.Final | ORM: маппинг entity, JPQL, кэш, optimistic locking (`@Version`) |
| Hibernate Micrometer | 7.4.5.Final | Метрики `hibernate.*` в Micrometer (без них гейджи Hibernate не регистрируются) |
| Hibernate Validator | 9.1.4.Final | Реализация Bean Validation (`@Valid`, кастомные ограничения) |
| Jakarta Validation API | 3.1.1 | API аннотаций валидации |
| Liquibase | 5.0.3 | Версионные миграции схемы (`db/changelog`); Hibernate в режиме `validate` сверяет entity с миграцией |
| H2 | 2.4.240 (test) | In-memory БД для JPA-слайс-тестов спецификаций поиска |

### Аутентификация и безопасность

| Технология | Версия | Решаемая задача |
|---|---|---|
| Spring Security | 7.1.1 | Фильтры запросов, методная безопасность, авторизация по ролям |
| Keycloak | 26.6 (image) | Внешний OAuth2 / OIDC-провайдер (профиль `keycloak`); в dev поднимается compose |
| JJWT (api / impl / jackson) | 0.13.0 | Внутренний JWT-контур: выпуск и проверка access/refresh-токенов |
| Nimbus JOSE+JWT | 10.9.1 | JOSE/JWT для Spring Security OAuth2 |
| BouncyCastle | 1.85 | Крипто-провайдер; поднят с транзитивного 1.81 (CVE, требование trivy) |
| Caffeine | 3.2.4 | Хранилище одноразовых id капчи (ограниченный размер + TTL) |

### Файлы и почта

| Технология | Версия | Решаемая задача |
|---|---|---|
| MinIO (Java SDK) | 8.6.0 | S3-клиент вложений заявок: метаданные — в БД, файлы — в бакете |
| okhttp-jvm / Okio | 5.1.0 / 3.15.0 | HTTP-транспорт SDK MinIO (JVM-вариант okhttp 5.x) |
| pgsty/silo | `RELEASE.2026-09-16T00-00-00Z` (image, сервис `minio`) | S3-совместимое объектное хранилище вложений на PostgreSQL |
| Spring Mail / Jakarta Mail API | 4.1.1 / 2.1.5 | Отправка email через SMTP |
| Angus Mail / Angus Activation | 2.0.5 / 2.0.2 | Рантайм-реализация Jakarta Mail |
| SMTP4Dev | 3.15.0 (image) | Локальный SMTP с UI для проверки писем при разработке |

### Наблюдаемость

| Технология | Версия | Решаемая задача |
|---|---|---|
| Micrometer (core / observation) | 1.17.1 | Метрики приложения, observation API |
| micrometer-registry-prometheus | 1.17.1 | Экспорт метрик в Prometheus-формате (`/actuator/prometheus`) |
| Prometheus Java Client | 1.7.0 | Экспозиция и форматирование метрик |
| Micrometer Tracing | 1.7.1 | API трассировки, bridge на OpenTelemetry |
| OpenTelemetry (API / SDK / exporter-otlp) | 1.62.0 | Сбор и OTLP-экспорт трейсов |
| opentelemetry-jdbc | 2.28.1-alpha | Спаны `db.client.query` на каждый JDBC-запрос (обёртка DataSource) |
| Context Propagation | 1.2.1 | Прокидывание контекста между асинхронными вызовами |
| Mimir / Tempo / Loki / Alloy / OTel Collector | 2.16.0 / 2.8.0 / 3.5.0 / v1.12.2 / 0.162.0 (image) | Хранение метрик / трейсов / логов, их сбор и доставка (см. [Наблюдаемость](docs/observability/overview.md)) |

### Документация API

| Технология | Версия | Решаемая задача |
|---|---|---|
| springdoc-openapi (starter webmvc ui) | 3.1.1 | OpenAPI-спека собирается из контроллеров и DTO — контракт не расходится с кодом; UI — `/swagger-ui.html`, спека — `/v3/api-docs` |
| Swagger Core / Annotations (jakarta) | 2.2.55 | Аннотации OpenAPI 3 в коде контроллеров и DTO |
| Swagger UI (webjar) | 5.32.14 | Интерактивный интерфейс спеки |

### Утилиты и кодогенерация

| Технология | Версия | Решаемая задача |
|---|---|---|
| Lombok | 1.18.46 | Геттеры/сеттеры/конструкторы/логгеры на этапе компиляции (`provided`) |
| MapStruct (+ processor) | 1.6.3 | Маппинг entity ↔ DTO: интерфейсы `@Mapper`, реализации генерируются при компиляции |
| lombok-mapstruct-binding | 0.2.0 | Совместимость процессоров Lombok и MapStruct: без него маппер не видит сгенерированные аксессоры |
| Guava | 33.4.8-jre | Общие утилиты (транзитивно, используется в коде) |
| Apache Commons (Lang3 / Collections4 / Text / Codec / IO / Compress) | 3.20.0 / 4.5.0 / 1.15.0 / 1.21.0 / 2.20.0 / 1.28.0 | Строки, коллекции, кодирование, работа с файлами |
| SLF4J / Logback / Log4j→SLF4J | 2.0.18 / 1.5.38 / 2.25.5 | Логирование (JSON-логи для Loki) |

### Тестирование

| Технология | Версия | Решаемая задача |
|---|---|---|
| spring-boot-starter-test (+ data-jpa-test, opentelemetry-test) | 4.1.1 | Тестовый контур Boot: срезы `@WebMvcTest` / `@DataJpaTest`, TestObservationRegistry |
| JUnit Jupiter | 6.0.3 | Unit- и интеграционные тесты |
| Mockito (+ junit-jupiter) | 5.23.0 | Моки; javaagent подключается в Surefire/Failsafe |
| AssertJ / Hamcrest / Awaitility | 3.27.7 / 3.0 / 4.3.0 | Утверждения, матчеры, ожидание асинхронных условий |
| JsonPath / JSONassert / XmlUnit | 2.10.0 / 1.5.3 / 2.11.0 | Проверка JSON/XML в ответах API |
| spring-security-test | 7.1.1 | Security-контекст в тестах (`@WithMockUser` и др.) |
| Testcontainers (postgresql, junit-jupiter) | 2.0.5 | Реальный PostgreSQL 18 в интеграционных тестах (`*IT`): миграции и SQL как в проде |
| GreenMail | 2.1.0 | In-memory SMTP-сервер для интеграционных тестов писем |
| k6 | 2.3.0 (image) | Нагрузочное тестирование полного пользовательского сценария |
| Node.js | 18+ | Решение PoW-капчи при сидировании (`scripts/load/solve-pow.js`) |

### Сборка и качество кода

| Инструмент | Версия | Решаемая задача |
|---|---|---|
| maven-compiler-plugin | с BOM Boot 4.1.1 | Компиляция `release=25`, annotation processors (MapStruct, Lombok, binding) |
| maven-surefire / failsafe plugin | 3.5.4 | Unit-тесты (`*Test`) / интеграционные (`*IT`) |
| Spotless | 2.44.5 | Единое форматирование (4 пробела, line length 140, порядок импортов) |
| maven-checkstyle-plugin + Checkstyle | 3.6.0 / 10.26.1 | Нейминг, импорты, дизайн; ошибка валит сборку на этапе `validate` |
| liquibase-maven-plugin | 5.0.3 | Миграции без контейнера приложения (`make migrate`) |
| spring-boot-maven-plugin | 4.1.1 | Fat-jar, фиксация main-class |
| Docker (multi-stage) | `maven:3.9.16-eclipse-temurin-25-noble` → `eclipse-temurin:25-jre-noble` | Сборка в контейнере и компактный рантайм-образ |
| hadolint / trivy / gitleaks | 2.12.0 / 0.58.2 / 8.21.2 | CI: линтер Dockerfile, скан уязвимостей образа (CRITICAL/HIGH), поиск секретов |
| GitHub Actions / Renovate | — | CI/CD (build → push-образ → deploy по тегу); автообновление зависимостей |

### Инфраструктура (Docker Compose)

| Сервис | Образ | Назначение |
|---|---|---|
| PostgreSQL | `postgres:18` | Основная реляционная БД (в т.ч. базы Keycloak и Silo) |
| Silo (сервис `minio`) | `pgsty/silo:RELEASE.2026-09-16T00-00-00Z` | S3-совместимое объектное хранилище вложений |
| Keycloak | `quay.io/keycloak/keycloak:26.6` | OAuth2 / OIDC провайдер |
| SMTP4Dev | `rnwood/smtp4dev:3.15.0` | Тестовый SMTP-сервер |
| Mimir | `grafana/mimir:2.16.0` | Хранение метрик (Prometheus-совместимый) |
| Tempo | `grafana/tempo:2.8.0` | Распределённая трассировка |
| Loki | `grafana/loki:3.5.0` | Агрегация логов |
| Alloy | `grafana/alloy:v1.12.2` | Сбор метрик/логов/трасс |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.162.0` | Экспорт трейсов в Tempo |
| k6 | `grafana/k6:2.3.0` | Нагрузочное тестирование |

## Запуск

Стек поднимается в Docker; Java 25 и Maven нужны только для разработки.

```
Подготовка → .env (make env) → секреты в .env
  ├─ Dev: контейнеры (make up) / хост из IDEA (make up-deps + Debug)
  ├─ Prod: pull из ghcr.io → make up-prod
  ├─ Тесты: make verify (хост) / make e2e-all (поднятый стек)
  ├─ Нагрузка: make load (k6)
  └─ CI/CD: push → build → push-образ → deploy по тегу
```

Все сценарии — построчно, с командами, портами и проверками — на странице
[`docs/getting-started.md`](docs/getting-started.md).

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

Ошибки HTTP-слоя централизованно преобразуются в RFC 7807-подобные ответы
через `GlobalExceptionHandler`.

## Конфигурация

Приложение не хранит значений настроек в коде: каждая настройка — переменная
окружения без дефолта. В файлах `application*.properties` записана только
схема — строки вида `${VAR}`. Не заданная переменная приводит к отказу старта
с явным указанием причины, а не к молчаливой работе на чужих значениях.
Среды различаются наборами профилей Spring и compose-файлов.

Подробнее: [`docs/configuration.md`](docs/configuration.md)

## Аутентификация

Упор сделан на JWT. Токен передаётся в http-only cookie, refresh-токен
хранится в БД как SHA-256 хеш и проходит ротацию; отзыв (логаут, смена
пароля) применяется немедленно — за счёт версии токенов, не дожидаясь
истечения срока. Профиль `keycloak` переключает проверку подписи на внешний
OAuth2-провайдер, модель токена и cookie-контур при этом сохраняются.

Подробнее: [`docs/authentication.md`](docs/authentication.md)

## Правила доступа

Упор сделан на JWT: личность и роль извлекаются из токена на каждом запросе,
а проверка прав выполняется на сервере в момент обработки — аннотациями
`@PreAuthorize` над сервисами валидации доступа. Роль всегда читается из БД,
поэтому блокировка пользователя или её понижение вступают в силу сразу, не
дожидаясь истечения токена.

Порталы имеют три уровня доступа: владелец, доверенный пользователь,
публичный. Заявки — две политики: `ALL_USERS` (все, кому доступен портал) и
`CREATOR_AND_PORTAL_USERS` (автор и пользователи портала). Отказ отдаётся как
`403`, чужая или отсутствующая сущность — как `404`, чтобы не раскрывать
факт существования записи.

Подробнее: [`docs/access-control.md`](docs/access-control.md)

## Оповещения

In-app оповещения доходят по двум каналам: основной — SSE, фолбэк —
long polling. Оба используют общий курсор `afterId` и отдают одни и те же
строки, поэтому клиент может переключиться между ними без потери событий.
Дополнительно уходят email-уведомления, а повторы напоминаний доставляет
фоновый планировщик.

Подробнее: [`docs/notifications.md`](docs/notifications.md)

## Рейт-лимиты

Ограничения считаются отдельно по клиентскому IP и по эндпоинту; превышение
отдаёт `429` с заголовком `Retry-After`. Счётчики живут в памяти процесса и
сбрасываются при рестарте, пороги задаются переменными окружения.

Подробнее: [`docs/rate-limits.md`](docs/rate-limits.md)

## Наблюдаемость

Три сигнала: метрики (Micrometer → Mimir), трейсы (OTel → Tempo) и логи с
`traceId`/`spanId` (→ Loki). Стек наблюдаемости поднимается отдельно от
приложения, приложение от него не зависит. Алерты обеспечены runbooks.

Подробнее: [`docs/observability/overview.md`](docs/observability/overview.md)

## API

Описание всех эндпоинтов, кодов ответов и соглашений контракта — в
[`docs/api.md`](docs/api.md). Машинная спека собирается из контроллеров:
`GET /v3/api-docs`, интерфейс — Swagger UI.

## Тесты и проверки

Юнит- и интеграционные тесты выполняются на хосте без Docker; e2e-сценарии —
только на поднятом dev-стеке; нагрузочное тестирование идёт по полному
пользовательскому сценарию через k6.

Подробнее: [`docs/testing.md`](docs/testing.md)

## Документация

| Документ | Содержание |
|---|---|
| [`getting-started.md`](docs/getting-started.md) | Сценарии запуска: dev, хост, рабочий контур |
| [`configuration.md`](docs/configuration.md) | Переменные окружения, профили, compose-файлы, почта, миграции |
| [`authentication.md`](docs/authentication.md) | JWT-контур, Keycloak, refresh-токены, CSRF, капча |
| [`access-control.md`](docs/access-control.md) | Уровни доступа к порталам, политики заявок, передача владения |
| [`notifications.md`](docs/notifications.md) | SSE, long polling, механизм пробуждения, повторы |
| [`rate-limits.md`](docs/rate-limits.md) | Каталог лимитов, добавление нового лимита |
| [`api.md`](docs/api.md) | Эндпоинты, коды ответов, соглашения контракта |
| [`testing.md`](docs/testing.md) | Тесты, e2e-сценарии, нагрузочное тестирование |
| [`observability/`](docs/observability/) | Метрики, трейсы, логи, SLO |
| [`runbooks/`](docs/runbooks/) | Реагирование на алерты |

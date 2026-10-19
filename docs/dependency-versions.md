# Версии зависимостей

Полный список технологий и зависимостей с версиями и решаемыми задачами.

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
| Mimir / Tempo / Loki / Alloy / OTel Collector | 2.16.0 / 2.8.0 / 3.5.0 / v1.12.2 / 0.162.0 (image) | Хранение метрик / трейсов / логов, их сбор и доставка (см. [Наблюдаемость](observability/overview.md)) |

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

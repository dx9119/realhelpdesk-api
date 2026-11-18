# Тесты и проверки

Все тесты проводятся только в dev-среде. Юнит-тесты — на хосте, без Docker, в тестовой
конфигурации `src/test/resources/application.properties`; интеграционные (`*IT`) — тоже на
хосте, но им нужен Docker (Testcontainers поднимает PostgreSQL 18 и MinIO); e2e — только на
запущенном dev-приложении (запустить: `make run-local`,
инфраструктура — `make dev-run`). В других средах тесты не запускаются.

> **Примечание:** `dev-run` поднимает monitoring (Mimir, Tempo, OTel Collector),
> но e2e-тесты работают и без него — они не зависят от наблюдаемости.

| Команда | Что делает |
|---|---|
| `make test` | Юнит-тесты и срезы (`@DataJpaTest` на H2) |
| `make verify` | То же + интеграционные `*IT` (failsafe, GreenMail) |
| `make check-code-style` | Проверки стиля (`spotless:check`, `checkstyle`) |
| `make check-ci-all` | CI-проверки локально: gitleaks (секреты в дереве и истории) |
| `./mvnw spotless:apply` | Форматирование кода |
| `./mvnw -q test-compile` | Компиляция main и test (через `validate`: spotless + checkstyle) |
| `make dev-openapi` | Снять OpenAPI-спеку с запущенного приложения в `openapi.json` |
| `make dev-openapi-types` | Сгенерировать TypeScript-типы (`npx openapi-typescript`) |
| `make dev-run` | Dev-среда: инфраструктура + наблюдаемость в фоне (БД, MinIO, smtp4dev, Keycloak, Mimir/Tempo/OTel) |
| `make run-local` | Запустить приложение на хосте (нужно для e2e) |

## Архитектура: unit и интеграционные тесты

Тесты жёстко разделены на два класса по именам и фазам сборки; правила стиля — в
[`code-style.md` §«Тесты»](code-style.md#тесты).

| | `*Test` (surefire, `make test`) | `*IT` (failsafe, `make verify`) |
|---|---|---|
| Контекст Spring | не поднимается: standalone-MockMvc (`MockMvcTester`), MockitoExtension | полный `@SpringBootTest`, профиль `test` |
| База данных | H2 (embedded) для `@DataJpaTest`-слайсов | PostgreSQL 18, Testcontainers |
| Миграции | Liquibase выключен, схема — `ddl-auto` самого теста | полный Liquibase + `hibernate validate` |
| Docker | не нужен | нужен (контейнеры PostgreSQL/MinIO) |
| `spring-boot-docker-compose` | выключен (`spring.docker.compose.enabled=false`) | выключен: контейнеры поднимает Testcontainers, а не compose-стек |

- `@SpringBootTest` в `*Test` запрещён checkstyle (`NoSpringContextInUnitTests`) —
  полный контекст живёт только в `*IT`.
- JPA-слайсы делятся по контурам: unit-слайсы (`CreatedAtPrePersistTest`,
  `TicketSpecificationTest`, `NPlus1GuardTest`) — `@DataJpaTest` на H2 с
  `ddl-auto=create-drop` в аннотации; IT-слайсы (`EmailLogRepositoryIT`,
  `UnsubscribedEmailRepositoryIT`, `NotificationRepositoryIT`,
  `JwtRefreshTokenRepositoryIT`, `EmailSendingIT`) — `@DataJpaTest` +
  `@AutoConfigureTestDatabase(replace = NONE)` на PostgreSQL из контейнера.

## Базовый класс интеграционных тестов

`AbstractIntegrationTest` (корень `com.ukhanov.realhelpdesk`) — общий родитель всех
`*IT`. Контейнеры — статические поля: по одному на прогон JVM, повторного старта не
будет ни при кэшировании контекстов Spring, ни при загрузке тест-классов другим
classloader'ом; остановку при выходе из JVM делает Ryuk.

- **PostgreSQL 18** (`postgres:18-alpine`) — `@ServiceConnection`: Boot сам публикует
  datasource из контейнера, ручной `@DynamicPropertySource` с URL не нужен.
- **MinIO** (`pgsty/silo`) — адрес и учётные данные привязываются через
  `@DynamicPropertySource`: Boot не знает `MinioConnectionDetails`, поэтому
  `@ServiceConnection` для него недоступен.

Все контексты работают с одной базой контейнера, поэтому схему создаёт Liquibase
(`spring.liquibase.*` в `application-test.properties`), а Hibernate её только
проверяет (`ddl-auto=validate`) — расхождение миграций с entity валит IT.
Побочный эффект общей БД — возможны утечки данных между IT-классами; при вылете
очистка добавляется локально в конкретный тест (`@Transactional`-откат слайсов,
в full-context — точечно).

`OperatorBootstrapIT` сохраняет собственный `@DynamicPropertySource` — только для
`operator.bootstrap.emails`, datasource-часть унаследована.

## Стандарты тест-кода

Все правила блокируются checkstyle (`config/checkstyle/checkstyle.xml`, подавления —
`config/checkstyle/suppressions.xml`), формулировки — в
[`code-style.md` §«Тесты»](code-style.md#тесты).

## Паттерны unit-тестов (`*Test`)

### Контроллер: MockMvcTester без контекста

Контроллер собирается руками вместе с заглушками сервисов; `GlobalExceptionHandler`
подключается как controller advice — так проверяются и успешные коды, и problem+json
ошибки валидации. Ассерты — AssertJ над `MvcTestResult`; JSON — через `bodyJson()`.

```java
@ExtendWith(MockitoExtension.class)
class TicketControllerTest {

    @Mock
    private TicketManageService mockTicketManageService;

    private MockMvcTester mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcTester.of(List.of(new TicketController(mockTicketManageService)),
                builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
    }

    @Test
    @DisplayName("POST /portals/{id}/tickets → 201 + Location на созданную заявку")
    void createTicket_validRequest_returnsCreatedWithLocation() throws Exception {
        // given
        when(mockTicketManageService.createTicket(any(CreateTicketRequest.class), eq(PORTAL_ID)))
                .thenReturn(new CreateTicketResponse(TICKET_ID));

        // when
        MvcTestResult result = mockMvc.post().uri("/api/v1/portals/{portalId}/tickets", PORTAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Ошибка входа\"}").exchange();

        // then
        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/api/v1/portals/5/tickets/77");
        assertThat(result).bodyJson().extractingPath("$.id").asNumber().isEqualTo(77);
    }
}
```

### Сервис: MockitoExtension

Заглушки — поля `@Mock`, проверяемый сервис собирается в `@BeforeEach`; имена
заглушек `mock*`, captor'ы `*Captor`. Дженерик-матчеры требуют явного свидетеля
типа, иначе вызов `findAll` двусмысленен (`Example` vs `Specification`):

```java
when(mockTicketRepository.findAll(ArgumentMatchers.<Specification<TicketModel>>any(), eq(DEFAULT_PAGE)))
        .thenReturn(page);
```

`@MockitoSettings(strictness = Strictness.LENIENT)` — только вместе с явными
`lenient().when(...)` в том же классе.

### JPA-слайс на H2

`@DataJpaTest` + `ddl-auto=create-drop` в аннотации (тестовый `application.properties`
фиксирует `spring.liquibase.enabled=false`, схему слайса создаёт сам H2):

```java
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("Выставление createdAt у сущностей (@PrePersist)")
class CreatedAtPrePersistTest {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("TicketModel → дата не задана → createdAt = текущее время")
    void ticket_absentCreatedAt_setsCurrentTime() {
        // given
        // when
        UserModel author = user();
        em.persist(author);
        ...
        em.persist(ticket);

        // then
        assertCreatedAtIsNow(reload(ticket, TicketModel.class).getCreatedAt());
    }
}
```

### Политика доступа: reflection-контракт

`AttachmentAccessPolicyTest` проверяет паритет `@PreAuthorize` контроллеров без
контекста — чтение аннотаций через reflection. Так дёшево фиксируются контракты,
которые невозможно сломать рефакторингом «молча».

## Паттерны интеграционных тестов (`*IT`)

### Полный контекст

`@SpringBootTest` + профиль `test` + наследование `AbstractIntegrationTest`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test")
@DisplayName("Actuator: управляющий порт, Basic-скрейп и закрытие /actuator на основном порту")
class ActuatorSecurityIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("health, liveness и readiness отвечают без аутентификации — пробы оркестратора работают")
    void healthProbes_withoutCredentials_returnsOk() {
        // given / when / then
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }
}
```

### Репозиторий на PostgreSQL 18

Слайс репозитория берёт datasource контейнера (`replace = NONE`) и работает на
схеме Liquibase из `db/changelog/db.changelog-master.xml`:

```java
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
@DisplayName("Журнал писем в БД: EmailLogRepository")
class EmailLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private EmailLogRepository repository;

    @Test
    @DisplayName("Считаются только письма нужного адресата, события и попавшие в окно")
    void countEmails_filteredByRecipientEventAndWindow_returnsTwo() {
        // given
        // when
        LocalDateTime now = LocalDateTime.now();
        log(RECIPIENT, NotificationEvent.RECOVERY_PASSWORD, now.minusMinutes(5));
        log(RECIPIENT, NotificationEvent.NEW_TICKET, now.minusMinutes(5));
        repository.flush();

        long count = repository.countEmailsByToAndEventBetween(RECIPIENT,
                NotificationEvent.RECOVERY_PASSWORD, now.minusHours(1), now);

        // then
        assertThat(count).isEqualTo(2);
    }
}
```

### Реальные внешние сервисы

- SMTP — GreenMail in-process (`EmailSendingIT`, captcha-IT): письмо проверяется
  целиком, включая темы/шаблоны.
- S3 — контейнер MinIO из базового класса: загрузка/скачивание идут через реальный
  протокол, заглушки `MinioClient` остаются только в unit-тестах.
- Ожидания асинхронного — Awaitility (`await().atMost(...)`), не паузы.

## Guard-тесты

Автоматические контракты архитектуры и конфигурации (корень
`src/test/java/com/ukhanov/realhelpdesk/`, идут в surefire):

| Тест | Контракт |
|---|---|
| `LiquibaseChangelogGuardTest` | DML только в `seed/` (`context="seed"`); `changes/` и `sql/` — чистый DDL: миграция с `DB_MIGRATIONS_CONTEXTS=schema` не внесёт данных |
| `ArchitectureTest` | Слои (ArchUnit): контроллеры не трогают репозитории и entity, сервисы — контроллеры, `domain` не зависит от `feature`; запреты `java.util.Date`/`java.sql.*` вне допустимых мест |
| `ApplicationConfigSchemaTest` | `application*.properties` — схема `${VAR}` / `${VAR:default}`: обязательные переменные (секреты, хосты) объявлены в `infrastructure/.env.dev.example`; `ddl-auto` закреплён как `validate`, `create-drop` в конфигурации среды запрещён |
| `ObservabilityRunbookGuardTest` | Биекция алертов Mimir и `docs/runbooks/*.md`; `overview.md` ссылается на каждый runbook; диагностические curl-команды исполняемы (PromQL/TraceQL — только через `-G --data-urlencode`) |
| `OpenApiDocumentationGuardTest` | Security-scheme описан; реальные статусы контроллеров (201/202/204/206/416) задокументированы `@ApiResponse`; контракт `schema-contract.json` полон (описание + пример у каждой схемы) |
| `CreatedAtPrePersistTest` | `@PrePersist` заполняет `createdAt` у всех сущностей и не перезаписывает заданный |
| `NPlus1GuardTest` | Число JDBC-запросов на горячих путях (пользователь, порталы, страница тикетов) не выросло — статистика Hibernate |

## Строгость сборки

Проверки включены всегда и не обходятся флагами (`checkstyle.skip`,
`spotless.check.skip` зафиксированы в `false`):

- **JDK 25** — `maven-enforcer-plugin` в фазе `validate` сверяет версию рантайма
  (`^25(\.|$).*`); сборка на другой JDK падает намеренно.
- **`-Werror -Xlint:deprecation -Xlint:unchecked`** — предупреждения компилятора
  становятся ошибками: deprecated- и unchecked-API не накапливаются незаметно.
- **`@{argLine}`** в surefire/failsafe — позднее подстановление: `-javaagent`
  Mockito объявлен в арглайнах плагинов, инструмент вроде JaCoCo сможет дописать
  свой агент, не затирая Mockito; свойство `argLine` объявлено пустым, чтобы
  `@{argLine}` резолвился всегда.
- Проверки стиля живут в фазе `validate`, поэтому и `./mvnw test-compile`,
  и `make build` (`package -DskipITs`) проходят через spotless/checkstyle —
  компиляция без них невозможна.
  В IDEA включите «Delegate IDE build/run actions to Maven» (см.
  [`code-style.md`](code-style.md#настройка-intellij-idea)), иначе IDE-сборка
  обходит все эти проверки.

### Локальная CI-верификация

`make check-ci-all` повторяет CI-джобы (обязательна перед коммитом, см. `AGENTS.md`):

1. **gitleaks** — секреты в дереве и истории коммитов.

## E2E-сценарии

Контур аутентификации один — Keycloak (OAuth2 Resource Server): сценарии
берут токен direct access grant'ом и ходят в API с `Authorization: Bearer`.
Cookie-эндпоинтов `/api/v1/auth/*` в приложении нет.

E2E-цели отвечают только за тесты: контейнеры не поднимают и не пересобирают.
Приложение запускается на хосте заранее — `make run-local`. Цель
`make require-app` лишь проверяет доступность приложения; без неё
e2e-прогон не запускается.

| Команда | Сценарий | Скрипт |
|---|---|---|
| `make e2e-all` | Полный прогон: вход, порталы, уведомления | `scripts/e2e-*.sh` |
| `make e2e-keycloak` | Агрегатор: три сценария подряд | `scripts/e2e-keycloak.sh`, `-kc.sh` |
| `make e2e-keycloak-auth` | Вход через Keycloak (OAuth2 RS, роли, блокировка) | `scripts/e2e-keycloak.sh` |
| `make e2e-portal-access-kc` | Портал через Keycloak (Bearer, CRUD) | `scripts/e2e-portal-access-kc.sh` |
| `make e2e-notifications-kc` | Уведомления через Keycloak (Bearer) | `scripts/e2e-notifications-kc.sh` |
| `make e2e-ratelimit` | Рейт-лимиты (ручной прогон; логика покрыта `RateLimitServiceTest`) | `scripts/e2e-ratelimit.sh` |

Сценарии не проверяют owner-transfer: передача владения доступна и через
Bearer-токен, но покрыта юнит-тестами `PortalOwnerTransferServiceTest`.

В `e2e-all` не входит `e2e-ratelimit`: логика бакетов, 429 и `Retry-After`
целиком покрыта `RateLimitServiceTest`, а прогон тратит десятки
последовательных запросов. Цель `make e2e-ratelimit` остаётся для ручного
прогона после изменений в рейт-лимитах (см. [`rate-limits.md`](rate-limits.md)).

Скрипты создают тестовых пользователей и тратят рейт-лимиты — при повторных
прогонах может потребоваться перезапустить приложение на хосте
(`Ctrl+C` + `make run-local`).

## Нагрузочное тестирование (k6)

Полный user journey под нагрузкой: каждый VU повторяет виток
«профиль → токен Keycloak → портал → заявка → сообщение → список сообщений →
поиск → unread-count → long-poll уведомлений»
с паузой `THINK_TIME` между итерациями. Скрипт —
`scripts/load/user-journey.js` (k6), обёртка — `scripts/load/run.sh`.

| Команда | Что делает |
|---|---|
| `make load-full` | Полный прогон: поднимает load-стек, сидит пул пользователей и гоняет journey (10 VU, 5 минут) |
| `make load-smoke` | Короткий smoke: 2 VU, 1 минута |

Параметры: `TARGET_VUS`, `DURATION`, `POOL_SIZE` (≤10), `THINK_TIME`,
`WAIT_TIMEOUT_SEC` — через окружение, например `TARGET_VUS=30 DURATION=15m make load-full`.
`RESET_LIMITS=1` — перезапустить app перед прогоном (обнуляет in-memory
счётчики рейт-лимитов, если предыдущий прогон исчерпал окно логина 10/300с).

### Как устроено

- **Пул пользователей** — пул до 10 `load-pool-<RUN_ID>-pN@load.test` сидирует
  `run.sh` через Keycloak Admin API: создаёт пользователя с паролем и
  `emailVerified=true` и назначает realm-роль `ROLE_USER`, которую
  `KeycloakAuthenticationConverter` переносит в БД при первом входе. VU
  распределяют пользователей по кругу (`VU1 → p1, VU2 → p2, …`).
- **Рейт-лимиты** — load-цели запускают приложение с `TRUST_PROXY_HEADERS=true`
  (`scripts/dev/run-local.sh`): каждая k6-VU шлёт уникальный `X-Forwarded-For`
  и получает свой bucket рейт-лимитов. Без этого все VU делят один
  `127.0.0.1` и упираются в общий лимит. Включать **только локально** — за
  таким запуском нет прокси.
- **Совместимость с наблюдаемостью** — `load-full`/`load-smoke` поднимают приложение на хосте с `OTLP_TRACING_ENDPOINT` на collector: без этого ключа экспорт трейсов откатился бы на `localhost:4318/v1/traces` (дефолт
  compose-интерполяции), сломав связку с запущенным obs-стеком
  (`make dev-run`). Прогон метрик/трейсов во время load-теста: поднимите
  `make dev-run`, затем `make load-smoke` — смотрите метрики в Mimir
  (`localhost:9090/prometheus`).
- **Токены** — каждая итерация VU получает access-токен direct access grant'ом
  Keycloak; cookie не используются, поэтому переиспользовать между витками
  нечего — токен запрашивается заново.
- **Капча** выключена локально (дефолт `CAPTCHA_ENABLED:false` в
  `application.properties`) — иначе выдача кода email под нагрузкой не пройдёт.

### Пороги

Падают с nonzero exit — прогон считается проваленным:

| Порог | Значение |
|---|---|
| `http_req_failed` | <1% |
| `checks` | >99% |
| `http_req_duration{search}` p95 | <500ms |
| `http_req_duration{ticket_create}` p95 | <1200ms |
| `http_req_duration{notifications_wait}` p95 | <6s (long-poll 4s) |

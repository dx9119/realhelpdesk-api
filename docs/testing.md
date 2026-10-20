# Тесты и проверки

Все тесты проводятся только в dev-среде. Юнит-тесты — на хосте, без Docker, в тестовой
конфигурации `src/test/resources/application.properties`; интеграционные (`*IT`) — тоже на
хосте, но им нужен Docker (Testcontainers поднимает PostgreSQL 18 и MinIO); e2e — только на
поднятом dev-стеке (`make dev-run` или `make up`). В других средах тесты не запускаются.

| Команда | Что делает |
|---|---|
| `make test` | Юнит-тесты и срезы (`@DataJpaTest` на H2) |
| `make verify` | То же + интеграционные `*IT` (failsafe, GreenMail) |
| `make compile` | Компиляция main и test (через `validate`: spotless + checkstyle, затем компиляция) |
| `make format` | Форматирование (`spotless:apply`) |
| `make check` | Проверки стиля (`spotless:check`, `checkstyle`) |
| `make check-ci` | CI-проверки локально: gitleaks + hadolint + trivy |
| `make openapi` | Снять OpenAPI-спеку с запущенного стека в `openapi.json` |
| `make openapi-types` | Сгенерировать TypeScript-типы (`npx openapi-typescript`) |
| `make dev-run` | Dev-среда: сборка и запуск стека в foreground (логи всех сервисов) |
| `make dev-test` | Dev-среда: `mvn verify` + все e2e-сценарии (нужен поднятый стек) |

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
[`code-style.md` §«Тесты»](code-style.md#тесты). Кратко и с id правил:

| Правило | id checkstyle |
|---|---|
| Утверждения только AssertJ; `Assertions` из JUnit, JUnit 4, TestNG — запрещены | `NoJUnitAssertions`, `NoJUnit4AndTestNg` |
| `@MockBean`/`@SpyBean` не используются (Boot 4: `@MockitoBean`) | `NoDeprecatedBootTestAnnotations` |
| `Thread.sleep()` запрещён — Awaitility (в `src/main` легитимен, подавлен) | `NoThreadSleepInTests` |
| `System.out/err`, `printStackTrace()` запрещены | `NoSystemOut` |
| Имя теста: `method_condition_outcome`; в `src/main` — camelCase без `_` | `TestMethodNamingConvention`, `MethodNameMain` |
| `@DisplayName` у каждого теста, по-русски | `RequireDisplayNameOnTests` |
| Секции `// given` → `// when` → `// then` в каждом тесте | `GivenWhenThenStructure` |
| Тест-класс package-private, суффикс `Test`/`IT` | `JUnit5PackagePrivate`, `TestClassSuffix` |
| `@SpringBootTest` только в `*IT` | `NoSpringContextInUnitTests` |

Helper-классы без тестов (`EmailTemplatesFixture`, `SseEmitterCapture`,
`AbstractIntegrationTest`) в подавлениях по `GivenWhenThenStructure` и
`TestClassSuffix`.

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

Тонкости MockMvcTester, проверенные на практике:

- async-эндпоинты (скачивание файлов, long polling) — `asyncExchange()`, обычный
  `exchange()` падает с «Async result ... was not set»; досбарка результата —
  `mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started.getMvcResult()))`;
- multipart — `.multipart().file(new MockMultipartFile(...)).param(...)`;
- заголовки cookie — `result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)`;
- `bodyJson().extractingPath("$.x")` → `.asNumber()` / `.asString()` / `.asBoolean()` /
  `.asArray()`, наличие поля — `hasPath("$.errors.title")`;
- `MockMvcTester.of(...)` принимает иvarargs контроллеров (`of(new AuthController(...))`),
  и пару `List` + кастомизатор билдера.

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
боевой схеме Liquibase:

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
| `ApplicationConfigSchemaTest` | `application*.properties` — только обязательные `${VAR}`; значения — в `.env.example` и `services.app.environment`; `ddl-auto` закреплён как `validate`, `create-drop` в prod-конфигах запрещён |
| `ObservabilityRunbookGuardTest` | Биекция алертов Mimir и `docs/runbooks/*.md`; `overview.md` ссылается на каждый runbook; диагностические curl-команды исполняемы (PromQL/LogQL — только через `-G --data-urlencode`) |
| `OpenApiDocumentationGuardTest` | Спека в prod выключена; security-scheme описан; реальные статусы контроллеров (201/202/204/206/416) задокументированы `@ApiResponse`; контракт `schema-contract.json` полон (описание + пример у каждой схемы) |
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
- Проверки стиля живут в фазе `validate`, поэтому `make compile` =
  `./mvnw validate test-compile` — компиляция без spotless/checkstyle невозможна.
  В IDEA включите «Delegate IDE build/run actions to Maven» (см.
  [`code-style.md`](code-style.md#настройка-intellij-idea)), иначе IDE-сборка
  обходит все эти проверки.

### Локальная CI-верификация

`make check-ci` повторяет CI-джобы (обязательна перед коммитом, см. `AGENTS.md`):

1. **gitleaks** — секреты в дереве и истории коммитов;
2. **hadolint** — линтер Dockerfile;
3. **trivy** — CRITICAL/HIGH уязвимости образа `realhelpdesk:ci` (с фиксой).

## E2E-сценарии

Сценарии идут по живому стеку (`make up`) — все сразу через `make e2e-all`
или по одному:

| Команда | Сценарий | Скрипт |
|---|---|---|
| `make e2e-portal-access` | Права доступа к порталу | `scripts/e2e-portal-access.sh` |
| `make e2e-ratelimit` | Рейт-лимиты (ручной прогон; логика покрыта `RateLimitServiceTest`) | `scripts/e2e-ratelimit.sh` |
| `make e2e-jwt` | JWT-поток аутентификации | `scripts/e2e-jwt-flow.sh` |
| `make e2e-notifications` | In-app оповещения (long polling) | `scripts/e2e-notifications.sh` |
| `make e2e-portal-transfer` | Передача владения порталом | `scripts/e2e-portal-transfer.sh` |
| `make e2e-token-cleanup` | Фоновая очистка refresh-токенов по расписанию (доступ и батчи покрыты `OperatorTokenCleanupIT`) | `scripts/e2e-token-cleanup.sh` |
| `make e2e-keycloak` | Вход через Keycloak (профиль `keycloak`) | `scripts/e2e-keycloak.sh` |
| `make e2e-all` | Базовые сценарии подряд | `scripts/e2e-*.sh` |

Сценарии `e2e-portal-access`, `e2e-jwt`, `e2e-notifications`,
`e2e-portal-transfer` и `e2e-token-cleanup` ходят в cookie-эндпоинты
`/api/v1/auth/*`, то есть во внутренний JWT-контур: цели `make` перед прогоном
переключают приложение на профиль `dev` (без `keycloak`). Контур Keycloak
проверяет `e2e-keycloak` — он переключает профиль обратно на `dev,keycloak`
и работает с Bearer-токеном, выпущенным Keycloak.

В `e2e-all` не входит `e2e-ratelimit`: логика бакетов, 429 и `Retry-After`
целиком покрыта `RateLimitServiceTest`, а прогон тратит 56 последовательных
запросов. Цель `make e2e-ratelimit` остаётся для ручного прогона после
изменений в рейт-лимитах (см. [`rate-limits.md`](rate-limits.md)).

Скрипты создают тестовых пользователей и тратят рейт-лимиты — при повторных
прогонах может потребоваться `make restart`. `e2e-token-cleanup` дополнительно
занимает сроки токенов в `infrastructure/.env` до секунд, пересоздаёт контейнер
`app` и по завершении возвращает конфиг и контейнер (в том числе по `Ctrl+C`).

## Нагрузочное тестирование (k6)

Полный user journey под нагрузкой: каждый VU повторяет виток
«профиль → (ре)логин → портал → заявка → сообщение → список сообщений →
поиск → unread-count → long-poll уведомлений → refresh access»
с паузой `THINK_TIME` между итерациями. Скрипт —
`scripts/load/user-journey.js` (k6), обёртка — `scripts/load/run.sh`.

| Команда | Что делает |
|---|---|
| `make load` | Полный прогон: сидит пул пользователей и гоняет journey (10 VU, 5 минут) |
| `make load-smoke` | Короткий smoke: 2 VU, 1 минута |
| `make up-load` | Только поднять стек с load-оверлеем (k6 запускать вручную) |

Параметры: `TARGET_VUS`, `DURATION`, `POOL_SIZE` (≤10), `THINK_TIME`,
`WAIT_TIMEOUT_SEC` — через окружение, например `TARGET_VUS=30 DURATION=15m make load`.
`RESET_LIMITS=1` — перезапустить app перед прогоном (обнуляет in-memory
счётчики рейт-лимитов, если предыдущий прогон исчерпал окно логина 10/300с).

### Как устроено

- **Пул пользователей** — пул до 10 `load-pool-<RUN_ID>-pN@load.test` сидирует
  `run.sh` (регистрация + psql-верификация email — портал создаёт только
  `isEmailVerified=true`). VU распределяют пользователей по кругу
  (`VU1 → p1, VU2 → p2, …`), общий лимит регистрации не превышается.
- **Рейт-лимиты** — `make up-load` передаёт `TRUST_PROXY_HEADERS=true`
  инлайном: каждая k6-VU шлёт уникальный `X-Forwarded-For` и получает свой
  bucket рейт-лимитов. Без этого профиля все VU делят один `127.0.0.1` и
  логин упирается в лимит 10/300с. Включать **только локально** — за таким
  профилем нет прокси.
- **Совместимость с наблюдаемостью** — `up-load`/`load-smoke` передают
  `OTLP_TRACING_ENDPOINT` на collector инлайном: app пересоздаётся, и без
  этого ключа экспорт трейсов откатился бы на `localhost:4318/v1/traces` (дефолт
  compose-интерполяции), сломав связку с запущенным obs-стеком
  (`make obs-up`). Прогон метрик/трейсов во время load-теста: поднимите
  `make obs-up`, затем `make load-smoke` — смотрите метрики в Mimir
  (`localhost:9090/prometheus`) и `make obs-alerts`.
- **Cookie jar** — явная per-VU jar (k6 сбрасывает встроенную между
  итерациями): access-token из логина живёт между витками, re-login
  происходит только по 401.
- **Капча** выключена локально (`CAPTCHA_ENABLED=false` в
  `infrastructure/.env`, дефолт шаблона) — иначе регистрация/логин под
  нагрузкой не пройдут.

### Пороги

Падают с nonzero exit — прогон считается проваленным:

| Порог | Значение |
|---|---|
| `http_req_failed` | <1% |
| `checks` | >99% |
| `http_req_duration{search}` p95 | <500ms |
| `http_req_duration{ticket_create}` p95 | <1200ms |
| `http_req_duration{notifications_wait}` p95 | <6s (long-poll 4s) |

Эталонный результат (10 VU, 5 минут, 669 итераций, локально): 99.8% checks
зелёных, 0.4% HTTP-ошибок, ticket_create p95 ≈ 43ms, search p95 ≈ 8ms,
~15 RPS. Отчёт k6 (RPS, percentiles по endpoint'ам, пороги) печатается
в stdout.

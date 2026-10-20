# Code Style — RealHelpDesk

Единые правила стиля кода для всего проекта. Правила закреплены инструментами и проверяются автоматически при каждой сборке.

## Инструменты

| Инструмент | Что делает | Где задан |
|---|---|---|
| EditorConfig | отступы, кодировка, переводы строк — для всех IDE | `.editorconfig` |
| Spotless (Eclipse JDT) | автоформатирование Java, порядок и чистка импортов | `pom.xml`, `config/eclipse/eclipse-format.xml` |
| Checkstyle | нейминг, импорты, дизайн, длина строк | `pom.xml`, `config/checkstyle/checkstyle.xml` |

```bash
mvn spotless:apply    # применить форматирование
mvn spotless:check    # проверить форматирование
mvn checkstyle:check  # проверить правила стиля
mvn validate          # обе проверки + компиляция (запускаются и на mvn test/package)
```

Конфиги подключены к фазе `validate` — сборка падает при нарушении стиля.

## Настройка IntelliJ IDEA

Форматтер Spotless (Eclipse JDT) — это библиотека внутри Maven-плагина, сама IDE Eclipse не нужна.
Чтобы IDEA форматировала код так же, как `mvn spotless:apply`:

1. `.editorconfig` IDEA читает автоматически (отступы, кодировка, LF, финальный `\n`) — ничего делать не нужно.
2. Импортируйте профиль форматирования:
   `Settings → Editor → Code Style → Java → ⚙ → Import Eclipse XML Profile…` →
   выбрать `config/eclipse/eclipse-format.xml`.
3. Опционально (точнее встроенный импорт): плагин **Eclipse Code Formatter** (`plugins.jetbrains.com/plugin/7104`),
   в его настройках указать тот же `config/eclipse/eclipse-format.xml` и включить форматирование при сохранении.
4. После импорта: `Settings → Editor → Code Style → Java → ⚙ → Override default color or font settings`
   не требуется — убедитесь, что выбран импортированный scheme (он становится активным автоматически).
5. Включите `Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → Delegate IDE build/run actions to Maven`.
   Иначе кнопка Run/Build в IDEA компилирует своим компилятором и обходит проверки фазы `validate`
   (Spotless, Checkstyle, enforcer JDK, `-Werror`): локально всё «зелёное», а `make verify`/CI падают.
   Цена — сборка из IDE идёт через Maven (медленнее), зато результат совпадает с командной строкой.

Перед коммитом проверьте себя: `mvn spotless:check` — если файлы «грязные», IDEA форматирует иначе,
либо переимпортируйте профиль, либо выполните `mvn spotless:apply`.


## Форматирование

- **Отступы:** 4 пробела, табуляции запрещены. Продолжения выражений — 8 пробелов (2 уровня).
- **Длина строки:** максимум 140 символов (длинные литералы разбивать конкатенацией).
- **Кодировка/переводы:** UTF-8, LF, файл заканчивается переводом строки, без хвостовых пробелов.
- **Скобки:** всегда `if (a) { ... }` — одиночные statement без `{}` запрещены.
- **Порядок членов класса:** `static final` поля → instance-поля → конструктор → public-методы.

## Импорты

Группы в таком порядке, разделённые пустой строкой, внутри группы — по алфавиту:

```java
import java.util.List;          // java
                                // (пустая строка)
import jakarta.validation.*;    // jakarta
                                // (пустая строка)
import org.slf4j.Logger;        // org
                                // (пустая строка)
import com.ukhanov...;          // com
                                // (пустая строка)
import static ...;               // static — всегда в конце
```

- **Wildcard-импорты (`import pkg.*;`) запрещены** — только явные имена.
- Статические импорты — только в тестах.
- Неиспользуемые и дублирующие импорты удаляет Spotless автоматически.

## Именование

| Элемент | Правило | Пример |
|---|---|---|
| Пакеты | `lowercase`, без `_`, единственное число | `feature.ticketmanager.service` |
| Классы | `PascalCase` + суффикс роли | `TicketManageService`, `CreateTicketRequest` |
| Методы | `camelCase`, глагол | `getPageTickets` |
| Тест-методы | `method_condition_outcome` (через `_`) | `getTicketById_nullId_throwsNPE` |
| Поля/переменные/параметры | `camelCase` | `currentUserProvider` |
| Константы | `UPPER_SNAKE_CASE` | `SORTABLE_TICKET_FIELDS` |
| Логгер | строчный `logger` из `@Slf4j` (`lombok.log.fieldName=logger`) | `logger.debug("...")` |

Суффиксы ролей: `*Service`, `*Controller`, `*Repository`, `*Mapper`, `*ExceptionHandler`, `*Config`, `*Request`, `*Response`, `*Exception`, `*Model` (JPA entity).

## Spring / Java-конвенции

- **Java 25**, `jakarta.*` (не `javax.*`); **Lombok** — `@Slf4j`, `@Getter`/`@Setter`, `@RequiredArgsConstructor`, `@Builder` не используется, `@Data` запрещён.
- **JPA entity** — только `@Getter @Setter` (без `@Data`: equals/hashCode/toString не должны трогать lazy-связи).
- **Constructor injection** — только через конструктор с `private final`-полями, `@Autowired` запрещён.
- Утилитарные классы (только static-методы): `final class` + приватный конструктор.
- DTO — `record` (и `PageResponse.Builder` для сборки страницы), без Lombok.
- `@Transactional` — только на методах, кроме `readOnly`-поисковых сервисов.
- Аннотации — с отдельной строки, порядок: stereotype → mapping/транзакция.

## Null-безопасность

- Границы доверия: `Objects.requireNonNull(value, "русское сообщение")`.
- В репозиториях — `Optional` + `orElseThrow(...)` с логированием.
- `@Nullable`/`@NonNull` не используются — контракт описывается requireNonNull и `Optional`.

## Исключения

- Одно checked-исключение `*Exception extends Exception` на подсистему, конструкторы `(String)` и `(String, Throwable)`.
- Проброс через `throws` до контроллера, ловится `@ControllerAdvice`-обработчиком (`*ExceptionHandler` с `@Order`).
- Ответ обработчика: `ResponseEntity<Map<String, String>>`, русские ключи/сообщения, HTTP 409 для бизнес-ошибок.

## Логирование и комментарии

- SLF4J: только `@Slf4j` — Lombok разворачивает аннотацию в `private static final Logger logger = LoggerFactory.getLogger(X.class)`, имя поля фиксирует `lombok.config` (`lombok.log.fieldName=logger`). Ручное `private static final Logger logger = ...` не используется: объявленное поле конфликтует с Lombok и оставляет аннотацию без эффекта.
- Уровни привязаны к профилям `dev` и `prod` (`application-dev.properties`, `application-prod.properties`):
  - `ERROR` — неожиданный сбой, со стеком. Без токенов, паролей, кодов восстановления и `toString()` сущностей.
  - `WARN` — отказ во входе, лимит запросов, отклонённый токен, доступ запрещён (403), конфликт версий, проглоченная ошибка.
  - `INFO` — завершённое бизнес-событие (создан, изменён, удалён, зарегистрирован, письмо отправлено) и ответ HTTP 4xx. Видно в `prod`.
  - `DEBUG` — трассировка шагов и успешные HTTP-запросы. Видно только в `dev`.
- Логировать путь через `LogSanitizer.uri(...)`: код восстановления пароля и токен подтверждения email идут в пути запроса. Email, имена файлов вложений и телефоны маскировать через `LogSanitizer.email(...)` / `emails(...)` / `fileName(...)` / `phone(...)` — полный адрес и имя файла в логах это ПДн.
- Не дублировать «метод вызван» в сервисе: строку запроса пишет `LoggingFilter`. Query-string не логировать. Исключение логируется один раз на границе (`GlobalExceptionHandler`): 4xx — WARN без стектрейса, 5xx — ERROR со стектрейсом; сервисный лог перед `throw` не повторяет его — только DEBUG.
- Формат логов: в профиле `dev` — читаемый паттерн (`logback-spring.xml`; в тестах `logback-test.xml`), в остальных профилях — JSON по ECS через `StructuredLogEncoder`.
- MDC (ключи `requestId` / `clientIp` / `uriRequest` / `role`, плюс `traceId` / `spanId` от OTel):
  - ключи брать только из констант `LoggingFilter.REQUEST_ID` / `CLIENT_IP` / `URI_REQUEST` / `ROLE`, не строками — иначе код и паттерн в `logback-spring.xml` разъедутся; `traceId`/`spanId` при включённом OTel кладёт `Slf4JEventListener`;
  - `role` по умолчанию `ANONYMOUS` кладёт `LoggingFilter`, после аутентификации значение перезаписывает контурный фильтр — `JwtAuthFilter` (внутренний JWT) или `KeycloakAuthenticationRoleFilter` (профиль `keycloak`), оба без префикса `ROLE_` — как в теге метрики; очистку в `finally` делает `LoggingFilter`;
  - новый MDC-ключ = правка читаемых паттернов в `logback-spring.xml` (dev) и `logback-test.xml` — в JSON-ветке поля берутся из MDC автоматически — плюс константа в `LoggingFilter`;
  - логи в фоновых задачах и в колбэках `DeferredResult`/`SseEmitter` обязаны либо восстановить контекст запроса-инициатора (снимок `MDC.getCopyOfContextMap()` до подмены, в `finally` — прежний контекст потока), либо явно задать технический `requestId`; поток, возвращаемый в пул, не должен уносить чужой MDC;
  - любая строка уровня `WARN` и выше, видимая в `prod`, обязана нести `requestId` — иначе инцидент не связать с запросом.
- Сообщения логов, тексты ошибок, `@DisplayName` — **по-русски**; имена кода, URL, ключи JSON — **по-английски**.
- Javadoc в `src/main` не обязателел; `//`-комментарии — только для «почему», не «что». `TODO`/`FIXME` не использовать (найти задачу).

## Тесты

Правила ниже блокируются checkstyle (`config/checkstyle/checkstyle.xml`) — нарушение валит
сборку; архитектура unit/IT и стандарты стиля — в [`testing.md`](testing.md).

- **JUnit 5 + Mockito + AssertJ**. Утверждения — только AssertJ: статические импорты
  `org.junit.jupiter.api.Assertions.*` запрещены (`IllegalImport`), как и JUnit 4/TestNG.
- **Изоляция от Spring**: юнит-тесты — standalone-MockMvc (`MockMvcTester`) или
  MockitoExtension, JPA-слайсы (`@DataJpaTest`) — на H2; полный контекст — только в `*IT`
  на PostgreSQL 18. `@SpringBootTest` в `*Test` запрещён (`NoSpringContextInUnitTests`).
- **Классы**: `<ТестируемыйКлас>Test` (unit, surefire) или `<ТестируемыйКлас>IT`
  (интеграционные, failsafe, Docker) — суффикс проверяется `TestClassSuffix`;
  package-private (`class X`, без `public` — `JUnit5PackagePrivate`), пакет зеркалит main.
- **Метод**: ровно три сегмента `method_condition_outcome` —
  `searchTickets_noAccessiblePortals_returnsEmptyPage` (`TestMethodNamingConvention`);
  методы в `src/main` — camelCase без `_` (`MethodNameMain`).
- **`@DisplayName` обязателен** у каждого `@Test`/`@ParameterizedTest`, по-русски
  (`RequireDisplayNameOnTests`).
- **Структура**: каждый тест-method размечен `// given` → `// when` → `// then`
  (`GivenWhenThenStructure`).
- **Без сна и печати**: `Thread.sleep()` запрещён (`NoThreadSleepInTests`) — ожидания через
  Awaitility; `System.out/err` и `printStackTrace()` запрещены (`NoSystemOut`) — логгер.
- **Spring Boot 4 API**: `@MockBean`/`@SpyBean` не используются (в тестах контекста —
  `@MockitoBean`); подключение БД в IT — `@ServiceConnection` (Testcontainers), не ручной
  `@DynamicPropertySource`.
- Аннотации `@Mock`/`@InjectMocks` — каждая на своей строке; заглушки `mock*`, captor'ы `*Captor`.
- Строгость Mockito: `@MockitoSettings(strictness = Strictness.LENIENT)` допустима только вместе с явным `lenient().when(...)`.

## Коммиты

Conventional Commits с русским описанием:

```
fix(auth): убрать fallback на сырой refresh-токен в поиске
test(model): проверить выставление createdAt у всех сущностей
style(core): привести импорты к единому порядку
```

Типы: `fix`, `feat`, `refactor`, `test`, `docs`, `style`, `chore`, `build`.

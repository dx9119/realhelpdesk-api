# План: внедрение стандартов тестирования и строгой блокировки компиляции

Поэтапная реализация инструкции из 4 разделов. Каждый шаг — отдельный коммит, после каждого
шага: `make check-ci` (обязательно, см. AGENTS.md) и релевантные Maven-проверки.
Ничего не коммитится, пока проверки не зелёные.

## Текущее состояние (зафиксировано 2026-10-20)

- 95 тестовых файлов: 83 `*Test.java`, 10 `*IT.java`, 2 helper-класса без тестов
  (`EmailTemplatesFixture`, `SseEmitterCapture`); 656 аннотаций `@Test`/`@ParameterizedTest`.
- Все 10 `*IT` наследуют `PostgresIntegrationTest` (singleton PostgreSQL 18, БД на контекст
  `it_<hash>`, `ddl-auto=create-drop`, Liquibase выключен, `@DynamicPropertySource`).
- Unit-тесты изолированы от Spring-контекста (standalone MockMvc / MockitoExtension);
  `@DataJpaTest`-слайсы идут на H2 (явно настроенного URL нет — embedded-H2 из starter-test).
- Эмпирический счётчик нарушений будущих правил checkstyle (проверено запуском черновика):

  | Правило | Нарушений | Объём правок |
  |---|---|---|
  | TestMethodNamingConvention (`method_condition_outcome`) | 429 | ~83 файла |
  | RequireDisplayNameOnTests | 29 | 6 файлов |
  | GivenWhenThenStructure (min=1/файл → все 655 методов) | 88 файлов без структуры | 83 `*Test` + IT |
  | IllegalImport (`Assertions.*` из JUnit) | 6 | PaginationAdapterTest, RateLimitServiceTest |
  | NoThreadSleepInTests | 3 (+1 в main, подавляется) | RateLimitServiceTest, UsedTokenStoreTest, TracingSmokeIT |
  | NoSpringContextInUnitTests (`@SpringBootTest` в `*Test`) | 5 | переименование в `*IT` |
  | JUnit5PackagePrivate, TestClassSuffix, NoSystemOut, MethodNameMain (src/main) | 0 | только блокирующее правило |

- Механика checkstyle 10.26.1 проверена эмпирически:
  - узлы XPath — `METHOD_DEF`/`CLASS_DEF` (не `METHOD`/`CLASS`);
  - `AbstractNode.atomize()` всегда бросает `UnsupportedOperationException` — нельзя писать
    `path[фильтр]/@text` в `matches()`/сравнении; рабочий паттерн —
    `IDENT[предикат][matches(@text, ...)]` (предикат с адресацией внутри самого узла);
  - подавления — `SuppressionFilter` уровня `Checker` + `config/checkstyle/suppressions.xml`,
    матчинг по `id` (DTD 1.2) и `files` — работает и для `Regexp*` (Checker), и для
    TreeWalker-правил; `id` модуля задаётся `<property name="id">` (DTD 1.3 не знает атрибута
    у `<module>`).

## Шаг 0 — Этот файл

- [x] Создать `docs/testing-standards-rollout.md` со всеми шагами.
- Коммит: `docs(plan): описать план внедрения стандартов тестирования`
- Верификация: `make check-ci`.

## Шаг 1 — Документация стандартов

- [ ] `docs/code-style.md` §«Тесты»: переписать под жёсткие правила —
  имя метода ровно в три сегмента `method_condition_outcome`; `@DisplayName` обязателен у
  каждого теста; секции `// given` / `// when` / `// then` в каждом тест-методе; класс —
  `*Test` (unit, H2/standalone) или `*IT` (полный контекст, PostgreSQL 18); только AssertJ
  (импорты из `org.junit.jupiter.api.Assertions` запрещены checkstyle); `Thread.sleep()`,
  `System.out/err`, `printStackTrace()` запрещены (Awaitility / логгер); `@MockBean`/`@SpyBean`
  не используются — Spring Boot 4 API (`@MockitoBean`, `@ServiceConnection`, `MockMvcTester`).
- [ ] `docs/testing.md`: архитектура unit/IT — разделение `*Test`/`*IT`, H2 vs PostgreSQL 18,
  singleton-контейнер `AbstractIntegrationTest`, Liquibase + `hibernate validate` в IT.
- [ ] `docs/code-style.md` §«Настройка IntelliJ IDEA»: пункт про
  «Delegate IDE build/run actions to Maven» (шаг 9).
- Коммит: `docs(testing): закрепить стандарты тестирования и разделение Test/IT`
- Верификация: `make check-ci`.

## Шаг 2 — Имена, @DisplayName, given/when/then (крупный объём)

- [ ] Переименовать 429 тестовых методов в `method_condition_outcome` (3 сегмента, латиница,
  первый сегмент — тестируемый метод/сценарий, далее условие и результат — по смыслу теста).
- [ ] Добавить `@DisplayName` (по-русски) к 29 тестовым методам без него.
- [ ] Аннотировать все 656 тест-методов секциями `// given` → `// when` → `// then`
  (в `*Test`, `*IT`; helper-классы без тестов не трогаем — они в подавлениях).
- Затронутые файлы: ~83 `*Test` + 10 `*IT`.
- Коммит: `test(standards): имена method_condition_outcome, @DisplayName и given/when/then во всех тестах`
- Верификация:
  - `./mvnw -o spotless:apply && ./mvnw -o spotless:check checkstyle:check test-compile`
  - временный прогон черновика новых правил (подмена `config/checkstyle/checkstyle.xml`) — 0
    нарушений по TestMethodNamingConvention / RequireDisplayNameOnTests / GivenWhenThenStructure;
  - `./mvnw -o test` — юнит-прогон зелёный;
  - `make check-ci`.

## Шаг 3 — AssertJ и Awaitility

- [ ] `PaginationAdapterTest`, `RateLimitServiceTest`: убрать static-импорты
  `org.junit.jupiter.api.Assertions.*` → AssertJ (`assertThat`).
- [ ] `RateLimitServiceTest:65`, `UsedTokenStoreTest:34`, `TracingSmokeIT:159`:
  `Thread.sleep()` → Awaitility (ожидание условия с таймаутом, без фиксированных пауз;
  учесть TTL UsedTokenStore — ожидание истечения через condition, не спящим потоком).
- Коммит: `refactor(test): AssertJ вместо Assertions из JUnit, Awaitility вместо Thread.sleep`
- Верификация: `./mvnw -o spotless:check checkstyle:check test` + черновик правил
  (IllegalImport, NoThreadSleepInTests = 0) + `make check-ci`.

## Шаг 4 — MockMvcTester (Spring Boot 4 API)

- [ ] 8 контроллерных unit-тестов (88 `mockMvc.perform(...)`) перевести на
  `MockMvcTester` (`MockMvcTesterBuilders.standaloneSetup`, AssertJ-ассерты),
  `@Autowired MockMvc` → нет; поле `MockMvcTester`.
- Коммит: `refactor(test): MockMvcTester вместо MockMvc в контроллерных тестах`
- Верификация: `./mvnw -o spotless:check checkstyle:check test` + `make check-ci`.
- Откат/оговорка: если API MockMvcTester не покрывает текущие ассерты (например, raw
  `andReturn()`-сценарии) — отчитаться и согласовать.

## Шаг 5 — Полный контекст из unit-фазы: 5 `*Test` → `*IT`

- [ ] `LogCorrelationAndMaskingTest`, `DbObservabilityTest`, `HttpObservabilityTest`,
  `ObservabilityContextTest`, `ActuatorSecurityTest` → `*IT` (переименование файла и класса).
- [ ] Расширить базовый класс IT (на момент шага — `PostgresIntegrationTest`, после шага 6 —
  `AbstractIntegrationTest`): полноконтекстные тесты идут на PostgreSQL 18, не на H2
  (стандарт: unit = H2, IT = PG18).
- [ ] Убедиться, что `**/*IT.java` в surefire-excludes/failsafe не потерян; прогнать
  `./mvnw -o verify` — новые IT должны выполняться в failsafe.
- Коммит: `refactor(test): вынести @SpringBootTest-тесты наблюдаемости и безопасности в *IT`
- Верификация: `./mvnw -o verify` + `make check-ci`.

## Шаг 6 — IT-архитектура: singleton container, Liquibase, @ServiceConnection (раздел 1.3–1.5)

- [ ] Переименовать `PostgresIntegrationTest` → `AbstractIntegrationTest` (10 наследников).
- [ ] `@ServiceConnection` вместо `@DynamicPropertySource`:
  - один статический `PostgreSQLContainer<?> postgres:18-alpine` в базовом классе с
    `@ServiceConnection` (spring-boot-testcontainers);
  - отказ от БД на контекст `it_<hash>` — все контексты работают с одной БД контейнера;
  - сохранить JVM-singleton (system properties `it.pg.*` / единый статический holder —
    защита от повторного старта при разных classloader).
- [ ] Полный Liquibase + Hibernate validate (раздел 1.3):
  - в IT: `spring.liquibase.enabled=true`,
    `spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml`,
    `spring.jpa.hibernate.ddl-auto=validate`;
  - проверить, что changelog покрывает все entity (иначе `validate` упадёт — фиксить
    changesets, это и есть цель проверки);
  - `spring.liquibase.enabled=false` остаётся в юнит-конфиге (слайсы/H2 идут без миграций).
- [ ] MinIO-контейнер в базовом классе (по инструкции): статический контейнер +
  привязка конфигурации (`@ServiceConnection`, если Boot знает `MinioConnectionDetails`,
  иначе явная привязка свойств — с комментарием почему). Подключать только если тестам
  нужен реальный MinIO; если контексту достаточно адреса — поднимать контейнер всё равно
  (стандарт), пустое подключение не открывается.
- [ ] Убрать/переписать `@DynamicPropertySource` в `OperatorBootstrapIT` (свойственные
  `operator.bootstrap.emails` — оставить, это не про datasource; datasource-путь уходит в базу).
- [ ] Обработать побочные эффекты общей БД: возможная утечка данных между классами при
  общем контексте — при вылете добавить очистку (`@BeforeEach` truncate / `@Transactional`),
  отчитаться о каждом случае.
- Коммит: `test(it): AbstractIntegrationTest с @ServiceConnection, Liquibase+validate и MinIO`
- Верификация:
  - `./mvnw -o verify` (все 15 IT на новой базе) — дважды подряд (проверка
    идемпотентности Liquibase и отсутствия утечек между прогонами);
  - `./mvnw -o spotless:check checkstyle:check` + `make check-ci`.

## Шаг 7 — pom.xml: строгая компиляция (раздел 2)

- [ ] Удалить явные `mockito-core` и `mockito-junit-jupiter` из `<dependencies>`
  (остаются через `spring-boot-starter-test`; `${mockito.version}` для `-javaagent` путей
  резолвится из spring-boot-starter-parent — проверить).
- [ ] Свойства `checkstyle.skip=false`, `spotless.check.skip=false` (невозможно обойти
  проверки флагами `-D…skip` по умолчанию).
- [ ] `maven-enforcer-plugin` (версию взять из dependencyManagement/-POM BOM) с правилом
  `requireProperty` на `java.version` (регулярка `^25`) в фазе `validate` — сборка на
  чужой JDK падает.
- [ ] `maven-compiler-plugin`: `-Werror -Xlint:deprecation -Xlint:unchecked` — предупреждения
  в ошибки; починить всё, что вскроется в `src/main` (и в тестах — они тоже компилируются).
- [ ] `@{argLine}` в surefire и failsafe (позднее подстановление для инструментов,
  вроде JaCoCo) + гарантировать определённое свойство `argLine` (пустое значение по
  умолчанию), чтобы `@{argLine}` резолвился; `-javaagent` mockito сохранить в argLine.
- Коммит: `build(maven): enforcer, -Werror, @{argLine}, убрать явный mockito`
- Верификация: `./mvnw -o clean verify` + `make check-ci`.

## Шаг 8 — checkstyle: правила блокировки (раздел 3)

- [ ] `config/checkstyle/checkstyle.xml` — добавить (по проверенному черновику):
  - Checker: `RegexpSingleline` id=`NoThreadSleepInTests` (только `src/test`,
    подавление `src/main` — `RefreshTokenCleanupService` легитимен);
  - Checker: `RegexpMultiline` id=`GivenWhenThenStructure` (min=1, max=999, подавление
    `src/main` и 3 helper-файлов без тестов);
  - Checker: `SuppressionFilter` → `${checkstyle.suppressions.file}` (optional=true);
  - TreeWalker: второй `MethodName` id=`MethodNameMain` (строгий camelCase, подавление
    `src/test`); `RegexpSinglelineJava` id=`NoSystemOut`; `MatchXpath` с id:
    `TestMethodNamingConvention`, `RequireDisplayNameOnTests`, `JUnit5PackagePrivate`,
    `NoSpringContextInUnitTests` (подавление `*IT.java`), `TestClassSuffix`;
  - `IllegalImport` (regexp): `org.junit.jupiter.api.Assertions…`,
    `@MockBean`/`@SpyBean`, JUnit 4 (`org.junit.{Test,Assert,runner,runners,…}`), TestNG.
- [ ] Новый `config/checkstyle/suppressions.xml` (DTD 1.2) с подавлениями по `id`+`files`.
- [ ] В pom (если нужно) — `suppressionsLocation=config/checkstyle/suppressions.xml`.
- Коммит: `build(checkstyle): добавить блокирующие правила тестовых стандартов`
- Верификация: `./mvnw -o spotless:check checkstyle:check verify` + `make check-ci`.

## Шаг 9 — Тестовое окружение и Makefile (раздел 4)

- [ ] `spring.docker.compose.enabled=false` в тестовых конфигах
  (`src/test/resources/application.properties`, `application-test.properties`,
  `application-prod.properties` если участвует) — тесты не ходят в Docker-инфру.
- [ ] `Makefile`: цель `compile` → `./mvnw validate test-compile` (компиляция всегда после
  checkstyle/spotless из фазы `validate`).
- [ ] Заметка в `docs/code-style.md` §«Настройка IntelliJ IDEA»: включить
  «Delegate IDE build/run actions to Maven», чтобы IDEA не обоходила проверки.
- Коммит: `build(test-env): отключить docker compose в тестах, compile через validate`
- Верификация: `./mvnw -o verify`, `make compile`, `make check-ci`.

## Финальная верификация

- [ ] `./mvnw -o clean verify` — зелёный (unit + IT + checkstyle + spotless в validate).
- [ ] `./mvnw -o verify` второй раз подряд — стабильность (общая БД, Liquibase).
- [ ] `make check-ci` — exit 0.
- [ ] Итоговый отчёт пользователю, предложение закоммитить/запушить.

## Риски и заранее согласованные решения

1. **Общая БД вместо `it_<hash>`** (следствие `@ServiceConnection`) — возможны утечки данных
   между IT-классами; реакция: локальная очистка в конкретном тесте, отчёт.
2. **Liquibase + `validate`** — впервые проверит соответствие changelog ↔ entity на CI;
   расхождения = правка changesets (функционально значимая работа, отдельный отчёт в шаге 6).
3. **Переименование 5 `*Test` → `*IT`** — они переезжают из surefire в failsafe (CI `verify`
   станет длиннее), теперь на PostgreSQL 18.
4. **MockMvcTester** — если конкретный ассерт не переносится 1-в-1, сохранить исходную
   формулировку для этого кейса и отчитаться.
5. **enforcer `requireProperty java.version=^25`** — локальная сборка на другой JDK будет
   падать намеренно.

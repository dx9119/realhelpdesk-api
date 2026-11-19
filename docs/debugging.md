# Отладка

Частые ошибки и способы их устранения.

## Запуск приложения на хосте (`IDEA`, `make run-local`)

### Трейсы не доходят до Tempo

В dev-профиле (`SPRING_PROFILES_ACTIVE=dev,host`) экспорт трейсов включён по умолчанию
на `localhost:4318/v1/traces` — Collector из `make dev-run`. При запущенном стеке
трейсы попадают в Tempo без дополнительных переменных.

Если Collector не поднят (`make run-local` без `make dev-run`), экспортёр каждые
~30 секунд пишет в лог:

```
ERROR i.o.exporter.internal.http.HttpExporter | Failed to export spans
java.net.ConnectException: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318
```

**Влияние:** только трейсы — они никуда не экспортируются. На запросы, БД и
health-эндпоинты ошибка не влияет.

**Решение:** поднять наблюдаемость `make dev-run`, либо отключить экспорт пустым
`OTLP_TRACING_ENDPOINT` (noop SDK, спаны остаются в памяти и MDC).

В non-dev профилях экспорт по умолчанию отключён — `OTLP_TRACING_ENDPOINT` без
дефолта в `application.properties`; адрес Collector'а задаёт `application-dev.properties`.

## Запуск тестов

### Ложная ошибка `WrongTypeOfReturnValue` после правки кода

Симптом: после изменения сервисов/контроллеров тесты падают с
`WrongTypeOfReturnValue` или `UnnecessaryStubbingException`, хотя моки не
менялись.

**Причина:** Surefire гоняет старые `.class`-файлы, если не запущен
`test-compile` (только `surefire:test`). Mockito берёт старые моки, но код
уже другой.

**Решение:** перед запуском тестов всегда делать `test-compile`:

```bash
./mvnw -q test-compile            # компиляция main и test
./mvnw surefire:test -Dtest='...' # только нужные тесты
```

## Проверка стиля

### Checkstyle / Spotless

```bash
make check-code-style     # spotless:check + checkstyle:check
./mvnw spotless:apply     # автоматическое форматирование
```

Если `check-code-style` падает, а `spotless:apply` говорит «0 files changed» — проверьте,
не коммитились ли файлы с разной кодировкой (CRLF → LF).

## Gitleaks

### Ложные срабатывания

Gitleaks может находить «секреты» в публичных данных (примеры токенов в
тестах, публичные ключи). Если это так — добавьте путь в
`.gitleaks.toml` в секцию `allowlist`.

## Команды

| Команда | Что делает |
|---|---|
| `make check-code-style` | spotless + checkstyle (без тестов) |
| `./mvnw -q test-compile` | validate (checkstyle/spotless) + компиляция main/test |
| `make verify` | все тесты + IT + jacoco + checkstyle |
| `make check-ci-all` | gitleaks: секреты в дереве и истории коммитов |

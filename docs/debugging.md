# Отладка

Частые ошибки и способы их устранения.

## Запуск приложения на хосте (`IDEA`, `make run-local`)

### Ошибка: `Failed to export spans` каждые ~30 секунд

Симптом: в логе приложения, запущенного из IDEA (конфигурация
`RealHelpDeskApplication`) или `make run-local`, периодически появляется:

```
ERROR i.o.exporter.internal.http.HttpExporter | Failed to export spans
java.net.ConnectException: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318
```

При `make run-local` адрес в ошибке — `localhost:4318` (дефолт compose-интерполяции
в `services.app.environment`, если `OTLP_TRACING_ENDPOINT` не задан инлайном).

**Причина:** экспортёр трейсов отправляет OTLP-спаны по
`OTLP_TRACING_ENDPOINT`. При запуске из хостового процесса (IDEA/`run-local`)
наблюдаемость обычно не нужна — коллектор не участвует в хостовом запуске,
и соединение отклоняется.

**Влияние:** только трейсы — они никуда не экспортируются. На запросы, БД и
health-эндпоинты ошибка не влияет.

**Решение:** ошибка не требует устранения — она возникает только при хостовом
запуске, когда наблюдаемость не нужна. Для контейнерного контура (где коллектор
есть) трейсы работают автоматически: `make dev-run` поднимает monitoring вместе
со стеком.

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

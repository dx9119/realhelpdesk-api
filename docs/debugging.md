# Отладка

Частые ошибки и способы их устранения.

## Сборка Docker-образа (`make check-ci-all`, `dev-docker-build`, `docker-compose build`)

### Ошибка: Maven падает с `No match found` / `404` / `Temporary shutdown`

Симптом: при сборке образа в RUN-шаге `mvn -B -q package -DskipTests` Maven
не может скачать одну из зависимостей и падает с ошибкой вида:

```
[ERROR] Failed to read artifact descriptor: ...: No match found
[ERROR] ...: Temporary shutdown.
```

**Причина:** BuildKit кэширует маунт `/root/.m2` контейнера сборки между
прогонами. Если при предыдущей сборке репозиторий (например, Eclipse
Foundation) был недоступен, в кэше окажется «битый» артефакт — ошибка
повторяется даже когда репозиторий восстановился.

**Решение:** очистить кэш-маунты BuildKit:

```bash
docker builder prune --filter type=exec.cachemount -f
```

Это удалит только кэш RUN-маунтов (в том числе `/root/.m2` контейнера сборки).
Образы, данные проекта и том `trivy-cache` не затрагиваются.
Следующий прогон перескачает зависимости заново.

> **Профилактика:** если сборка образа упала из-за сетевой ошибки, всегда
> начинайте с `docker builder prune --filter type=exec.cachemount -f`,
> прежде чем искать проблему в конфигурации.

## Запуск приложения на хосте (`IDEA`, `make run-local`)

### Ошибка: `Failed to export spans` каждые ~30 секунд

Симптом: в логе приложения, запущенного из IDEA (конфигурация
`RealHelpDeskApplication`) или `make run-local`, периодически появляется:

```
ERROR i.o.exporter.internal.http.HttpExporter | Failed to export spans
java.net.ConnectException: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318
```

При `make run-local` адрес в ошибке — `otel-collector`: значение приходит из
`OTLP_TRACING_ENDPOINT` в `infrastructure/.env`.

**Причина:** экспортёр трейсов отправляет OTLP-спаны по
`OTLP_TRACING_ENDPOINT`, но `otel-collector` живёт только в compose-сети
профиля `monitoring` и портов на хост не публикует — хостовому процессу
коллектор недоступен, соединение отклоняется.

**Влияние:** только трейсы — они никуда не экспортируются. На запросы, БД и
health-эндпоинты ошибка не влияет.

**Решение:** ошибка не требует устранения. Заглушить —
`TRACING_SAMPLING_PROBABILITY=0` в env запуска (спаны не сэмплируются,
экспортёр не пытается отправлять). Трейсы смотреть в контейнерном контуре:
`make obs-up`, Tempo — `localhost:3200`
([`observability/overview.md`](observability/overview.md)).

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
| `make check-ci-all` | gitleaks + hadolint + сборка образа + trivy |
| `docker builder prune -f` | полная очистка кэша BuildKit |
| `docker builder prune --filter type=exec.cachemount -f` | только кэш RUN-маунтов |

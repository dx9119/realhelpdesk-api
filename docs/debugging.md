# Отладка

Частые ошибки и способы их устранения.

## Сборка Docker-образа (`make check-ci`, `docker-build`, `docker-compose build`)

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
./mvnw -q test-compile   # или make compile
./mvnw surefire:test -Dtest='...'
```

## Проверка стиля

### Checkstyle / Spotless

```bash
make check          # spotless:check + checkstyle:check
make format         # spotless:apply (автоматическое форматирование)
```

Если `check` падает, но `format` говорит «0 files changed» — проверьте,
не коммитились ли файлы с разной кодировкой (CRLF → LF).

## Gitleaks

### Ложные срабатывания

Gitleaks может находить «секреты» в публичных данных (примеры токенов в
тестах, публичные ключи). Если это так — добавьте путь в
`.gitleaks.toml` в секцию `allowlist`.

## Команды

| Команда | Что делает |
|---|---|
| `make check` | spotless + checkstyle (без тестов) |
| `make compile` | validate (checkstyle/spotless) + компиляция main/test |
| `make verify` | все тесты + IT + jacoco + checkstyle |
| `make check-ci` | gitleaks + hadolint + сборка образа + trivy |
| `docker builder prune -f` | полная очистка кэша BuildKit |
| `docker builder prune --filter type=exec.cachemount -f` | только кэш RUN-маунтов |

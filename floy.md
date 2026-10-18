# Флоу сборки и запуска RealHelpDesk

## 1. Dev-среда (локальная разработка)

### 1.1 Первый запуск

```bash
# 1. Создать .env из шаблона (секреты сгенерируются автоматически, если пусты)
make env

# 2. Поднять полный стек (infra + dev + app, сборка образа)
make up
```

После старта:
- API: `https://localhost:8443`
- Swagger: `https://localhost:8443/swagger-ui.html`
- Actuator: `http://localhost:8081/actuator/health`
- Grafana (если поднят): `http://localhost:3001`

### 1.2 Повседневная разработка

| Сценарий | Команда | Время |
|---|---|---|
| Поднять стек (первый или после `down`) | `make up` | 1–2 мин |
| Поднять только инфраструктуру (БД, MinIO, smtp4dev, Keycloak) | `make up-deps` | секунды |
| Запустить app на хосте против dockerized-инфры | Debug в IDEA (конфиг `RealHelpDeskApplication`) или `make run-local` | секунды |
| Перезапустить app (быстрый цикл) | `make restart` | секунды |
| Применить изменения конфига (.env) | `make apply-config` | секунды |
| Остановить стек | `make down` | секунды |
| Полная очистка (с удалением томов) | `make down-volumes` | секунды |

### 1.3 Inner loop: правка Java-кода

**Вариант A — в контейнере** (штатный):
```bash
make up          # пересборка слоя app (docker layer cache из m2 ускоряет)
```
Любая правка Java требует пересборки образа.

**Вариант B — на хосте** (быстрый):
```bash
make up-deps    # один раз: поднять infra + local-tools
# Debug в IDEA (конфиг RealHelpDeskApplication, профили dev,host) —
# при отсутствии стека инфру поднимет spring-boot-docker-compose сам;
# либо CLI:
make run-local  # spring-boot:run на хосте (нужен JDK 21)
```
Правка кода → Hot-reload Spring Boot DevTools (секунды).

### 1.4 Тесты

```bash
make test        # юнит-тесты (H2, быстро)
make verify      # юнит + интеграционные *IT (Testcontainers PostgreSQL)
make check       # spotless:check + checkstyle:check
make format      # spotless:apply
```

### 1.5 E2E-тесты

```bash
make e2e-all     # все сценарии (требует поднятого стека)
make e2e-jwt     # конкретный сценарий
```

### 1.6 Профили аутентификации

```bash
make profile-local    # внутренний JWT (cookie) — по умолчанию
make profile-keycloak # Keycloak (openid-connect)
```

---

## 2. Prod-среда (VPS)

### 2.1 Архитектура

```
[Клиент] → Nginx (VPS, TLS/LE) → 127.0.0.1:8080 (app container, loopback)
```

- TLS завершает Nginx на хосте (Certbot/Let's Encrypt).
- App биндится **только на loopback** — наружу недоступен.
- `TRUST_PROXY_HEADERS=true` — Spring читает `X-Forwarded-For`.
- Сборка на сервере **запрещена** — только `pull` из ghcr.io.

### 2.2 Ручной деплой

```bash
# На сервере:
cd /opt/realhelpdesk

# 1. Синхронизировать IMAGE_TAG в .env
# (настраивается CI/CD, но можно вручную)
echo "IMAGE_TAG=sha-abc123" >> infrastructure/.env

# 2. Pull нового образа
docker compose -f infrastructure/docker-compose.yaml \
  -f infrastructure/docker-compose.prod.yaml \
  --profile infra --profile app pull app

# 3. Поднять prod-стек
make up-prod

# 4. Проверить health
curl -sf http://127.0.0.1:8081/actuator/health
```

### 2.3 Автоматический деплой (CI/CD)

```
git tag v1.2.3
  └─► GitHub Actions (deploy.yml)
       └─► SSH на VPS
            └─► Записать IMAGE_TAG в .env
            └─► docker compose pull app
            └─► make up-prod
            └─► Health-gate (до 90 сек)
                 ├─► OK → деплой завершён
                 └─► FAIL → авто-откат на предыдущий IMAGE_TAG
```

**Триггеры:**
- Пуш тега `v*` — автоматически
- `workflow_dispatch` — ручной запуск с указанием `image_tag`

### 2.4 Prod-preflight

При запуске с `SPRING_PROFILES_ACTIVE=prod` выполняется `ProdPreflightValidator`:
- Капча включена
- CORS настроен строго
- Keystore проверен (если TLS внутренний)
- SMTP доступен
- Миграции применены
- `ddl-auto=validate` не может быть отключён env'ом

### 2.5 Бэкапы

```bash
# Создать бэкап БД + MinIO
make backup

# Восстановить
make restore BACKUP_FILE=backups/db_2026-10-18_03-00.dump
```

На сервере рекомендуется crontab:
```
0 3 * * * cd /opt/realhelpdesk && make backup
```

---

## 3. CI/CD (GitHub Actions)

### 3.1 CI (на push в main / PR)

```
job: build
  └─► spotless:check + checkstyle:check + verify

job: docker
  └─► hadolint → сборка образа → trivy → gitleaks
  └─► (push в ghcr.io при push в main)

job: e2e
  └─► make env → make up → make e2e-all
```

### 3.2 Deploy (по тегу)

```
tag v*
  └─► job: deploy (environment: production)
       └─► SSH на VPS
            └─► pull + up-prod + health-gate + rollback
```

---

## 4. Переменные окружения

Переименований нет: приложение читает `infrastructure/.env` напрямую
(`DB_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` — те же имена, что
в compose и `.env.example`).

- Контейнер app: `env_file: ./.env` в `infrastructure/docker-compose.yaml`.
- Хост (IDEA/CLI): EnvFile (`.env` в run-конфиге IDEA) / построчная загрузка
  `.env` в `scripts/dev/run-local.sh` + профиль Spring `host`
  (`application-host.properties`) — loopback-адреса инфры и
  spring-boot-docker-compose. Run-конфиги инлайном добавляют
  `MINIO_ENDPOINT=http://127.0.0.1:9000` и дефолты
  `OTLP_TRACING_ENDPOINT`/`TRUST_PROXY_HEADERS`/`CLIENT_IP_FORWARDED_INDEX`
  (OS-env из `.env` перекрывает профильные файлы).

---

## 5. Полезные команды

```bash
make help              # список всех команд
make logs              # логи app (follow)
make shell             # sh в контейнере app
make ps                # статус контейнеров
make migrate           # Liquibase migration (хост)
make db-seed           # демо-данные (context=seed)
make obs-up            # поднять наблюдаемость (Grafana, Mimir, Tempo...)
make obs-alerts        # состояния алертов
make load              # нагрузочное тестирование (k6)
make check-image       # проверка образа (trivy, gitleaks, secrets)
```

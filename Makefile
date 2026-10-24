# RealHelpDesk — быстрые команды разработки.
# Запуск: make help

MVN ?= ./mvnw
IMAGE ?= realhelpdesk:latest
# Образ для проверок CI (hadolint/trivy job в .github/workflows/ci.yml)
CI_IMAGE ?= realhelpdesk:ci
APP_JAR ?= target/realhelpdesk-0.0.1-SNAPSHOT.jar
E2E_DIR ?= scripts

# Ревизия git для LABEL образа (org.opencontainers.image.revision)
GIT_SHA ?= $(shell git rev-parse HEAD 2>/dev/null || echo unknown)

# Compose-файлы: base — ядро (app, postgres, minio, local-tools),
# obs — наблюдаемость (mimir, tempo, loki, otel-collector, alloy),
# load — k6. Локальные цели подключают docker-compose.override.yaml
# (порты БД/MinIO на loopback хоста — для IDEA/psql/MinIO-консоли).
# Рабочий контур (COMPOSE_PROD) — base + prod: оверлей не участвует,
# на хост не публикуется ничего.
COMPOSE ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.override.yaml
COMPOSE_OBS ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.obs.yaml -f infrastructure/docker-compose.override.yaml
COMPOSE_LOAD ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.load.yaml -f infrastructure/docker-compose.override.yaml
# Рабочий контур: override без сборки на сервере (pull из ghcr.io)
COMPOSE_PROD ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.prod.yaml
PROFILES_DEPS ?= --profile infra --profile local-tools
PROFILES_LOCAL ?= $(PROFILES_DEPS) --profile app
PROFILES_PROD ?= --profile infra --profile app
PROFILES_OBS ?= $(PROFILES_LOCAL) --profile monitoring
PROFILES_ALL ?= --profile infra --profile local-tools --profile app --profile monitoring --profile load

.DEFAULT_GOAL := help

# Префикс dev- = цель локального dev-контура (compose-цепочка с override,
# порты на хост): на prod-сервере не запускается — там только prod-up/prod-backup/prod-restore.
.PHONY: help build clean compile package test verify format check run \
        dev-run dev-test run-local dev-up-deps \
        env dev-apply-config dev-docker-build dev-up prod-up dev-up-load dev-down dev-down-volumes \
        dev-logs dev-ps dev-restart dev-shell check-image \
        check-ci check-secrets check-dockerfile ci-image check-image-vuln \
        prod-backup prod-restore \
        dev-profile-local dev-profile-keycloak \
        obs-up obs-down obs-reload obs-alerts \
        load load-smoke \
        dev-migrate dev-db-seed \
        dev-openapi dev-openapi-types \
        e2e-reset-limits \
        e2e-portal-access e2e-ratelimit e2e-jwt e2e-notifications e2e-portal-transfer e2e-token-cleanup e2e-keycloak \
        e2e-portal-access-kc e2e-notifications-kc \
        e2e-jwt-in-app e2e-keycloak e2e-all

help: ## Показать список команд
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

## --- Сборка и тесты (Maven, на хосте) ---

build: ## Собрать jar без тестов (package -DskipTests)
	$(MVN) -DskipTests package

clean: ## Очистить target/
	$(MVN) clean

compile: ## Компиляция main и test (после checkstyle/spotless из фазы validate)
	$(MVN) validate test-compile

package: ## Полная сборка: тесты + jar
	$(MVN) package

test: ## Юнит-тесты (surefire)
	$(MVN) test

verify: ## Тесты + интеграционные *IT (failsafe)
	$(MVN) verify

format: ## Форматирование кода (spotless:apply)
	$(MVN) spotless:apply

check: ## Проверки стиля (spotless:check + checkstyle)
	$(MVN) spotless:check checkstyle:check

run: ## Запустить приложение локально (spring-boot:run)
	$(MVN) spring-boot:run

run-local: env ## Приложение на хосте против dockerized-инфры (профили dev,host; инфру поднимает spring-boot-docker-compose или make dev-up-deps)
	bash scripts/dev/run-local.sh

dev-run: env ## Dev-среда: compose up в foreground (логи всех сервисов, Ctrl+C — стоп)
	$(COMPOSE) $(PROFILES_LOCAL) up --build

dev-test: ## Dev-среда: mvn verify + e2e-сценарии (нужен поднятый стек: dev-run/dev-up)
	$(MVN) verify
	$(MAKE) e2e-all

## --- Docker ---

env: ## Создать infrastructure/.env и сгенерировать пустые локальные секреты
	bash scripts/docker/init-secrets.sh

dev-apply-config: ## Применить изменения .env к запущенному стеку (профиль app сохраняется)
	@# Профиль задаётся не в .env, а инлайном (dev-profile-local/dev-profile-keycloak): без переноса
	@# значения из работающего контейнера пересоздание вернуло бы .env/дефолт и
	@# молча сменило контур. Пустой profile (app не запущен) даст compose-дефолт.
	@profile=$($(COMPOSE) $(PROFILES_ALL) exec -T app printenv SPRING_PROFILES_ACTIVE 2>/dev/null | tr -d '');
	SPRING_PROFILES_ACTIVE="${profile}" $(COMPOSE) $(PROFILES_ALL) up -d --force-recreate --no-deps app

dev-docker-build: ## Собрать docker-образ приложения (с меткой GIT_SHA)
	$(COMPOSE) $(PROFILES_LOCAL) build --build-arg GIT_SHA=$(GIT_SHA)

dev-up: env ## Поднять локальный стек (профили infra+local-tools+app, сборка образа, в фоне)
	$(COMPOSE) $(PROFILES_LOCAL) up --build -d

dev-up-deps: env ## Поднять только инфраструктуру (infra+local-tools: БД, MinIO, smtp4dev, Keycloak) — для запуска app из IDEA/CLI
	$(COMPOSE) $(PROFILES_DEPS) up -d

prod-up: env ## Поднять рабочий стек (infra+app, pull из ghcr.io; настройки — «Рабочий контур» в .env)
	$(COMPOSE_PROD) $(PROFILES_PROD) up -d --no-build

dev-down: ## Остановить стек (все профили)
	$(COMPOSE) $(PROFILES_ALL) down

dev-down-volumes: ## Остановить стек и удалить тома (данные БД/MinIO)
	$(COMPOSE) $(PROFILES_ALL) down -v

## --- Бэкапы (PostgreSQL + MinIO; артефакты — ./backups/) ---

prod-backup: env ## Бэкап БД (pg_dump -Fc) и MinIO в ./backups/ + ротация старше 14 дней
	bash scripts/docker/backup.sh

prod-restore: env ## Восстановить БД из дампа: make prod-restore BACKUP_FILE=backups/db_....dump
	@test -n "$(BACKUP_FILE)" || (echo "Укажите BACKUP_FILE=path/to/db_....dump" && exit 1)
	@test -f "$(BACKUP_FILE)" || (echo "Файл не найден: $(BACKUP_FILE)" && exit 1)
	@echo "==> Останавливаю app"
	$(COMPOSE) $(PROFILES_PROD) stop app
	@echo "==> Восстанавливаю $(BACKUP_FILE) в БД"
	docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres \
	  pg_restore -U "$${POSTGRES_USER:-user}" -d "$${POSTGRES_DB:-desk}" --clean --if-exists < "$(BACKUP_FILE)"
	@echo "==> Поднимаю app"
	$(COMPOSE) $(PROFILES_PROD) up -d --no-build app
	@echo "==> Восстановление завершено"

## --- Наблюдаемость (подробности: docs/observability/overview.md) ---

obs-up: env ## Поднять стек + наблюдаемость (профили +monitoring; Mimir :9090, Loki :3100, Tempo :3200)
	OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces $(COMPOSE_OBS) $(PROFILES_OBS) up --build -d --remove-orphans

obs-down: ## Остановить стек с наблюдаемостью (все профили, томы целы)
	$(COMPOSE_OBS) $(PROFILES_OBS) down

obs-reload: ## Перечитать правила алертов Mimir (нужен obs-up; mimir/rules/anonymous/realhelpdesk.yml)
	$(COMPOSE_OBS) $(PROFILES_OBS) restart mimir && echo "Mimir перезапущен — правила перечитаны"

obs-alerts: ## Текущие состояния алертов Mimir (нужен obs-up)
	curl -s localhost:9090/prometheus/api/v1/alerts | grep -oE '"alertname":"[^"]+"|"state":"[^"]+"' | paste - - || echo "Mimir недоступен"

dev-logs: ## Логи приложения (follow)
	$(COMPOSE) $(PROFILES_ALL) logs -f app

dev-ps: ## Статус контейнеров (все профили)
	$(COMPOSE) $(PROFILES_ALL) ps

dev-restart: ## Перезапустить только приложение
	$(COMPOSE) $(PROFILES_ALL) restart app

# Переключение контура — инлайном: SPRING_PROFILES_ACTIVE объявлен в
# services.app.environment compose-файла (${SPRING_PROFILES_ACTIVE:-dev,keycloak}),
# поэтому переменная окружения хоста перекрывает infrastructure/.env и ничего
# на диск не пишется. Compose сравнивает конфигурацию контейнера: при смене
# профиля app пересоздаётся сам, при том же значении команда — no-op (не
# перезапускает стек зря). --wait дожидается healthcheck, чтобы следующая
# команда (e2e-сценарий) не била в ещё поднимающийся app. Если стек был
# остановлен, up поднимает его целиком с нужным профилем.
dev-profile-local: env ## Переключить приложение на внутренний JWT-контур (профиль dev) и пересоздать app
	SPRING_PROFILES_ACTIVE=dev $(COMPOSE) $(PROFILES_LOCAL) up -d --wait

dev-profile-keycloak: env ## Переключить приложение на контур Keycloak (профиль dev,keycloak) и пересоздать app
	SPRING_PROFILES_ACTIVE=dev,keycloak $(COMPOSE) $(PROFILES_LOCAL) up -d --wait

dev-shell: ## Shell внутри контейнера app
	$(COMPOSE) $(PROFILES_ALL) exec app sh

check-image: dev-docker-build ## Проверки образа: секреты, ФС, jar
	./scripts/docker/check-image.sh $(IMAGE)

## --- Локальные проверки CI (те же команды, что job docker в .github/workflows/ci.yml) ---
# Гонять перед коммитом/пушем — ловит то же самое, что упало бы в CI.
# Порядок: быстрые сначала (секреты, Dockerfile), trivy последним —
# ему нужна сборка образа. check-image выше — другой набор (гигиена ФС/истории
# образа), он CI не дублируется.

check-secrets: ## gitleaks: секреты в дереве и истории коммитов (как в CI)
	docker run --rm -v "$(CURDIR):/repo" zricethezav/gitleaks:v8.21.2 detect --source /repo --redact

check-dockerfile: ## hadolint: линтер Dockerfile (как в CI)
	docker run --rm -i hadolint/hadolint:2.12.0 < Dockerfile

ci-image: ## Собрать CI-образ (CI_IMAGE, по умолчанию realhelpdesk:ci) для trivy (как в CI)
	docker build --build-arg GIT_SHA=$(GIT_SHA) -t $(CI_IMAGE) .

# --timeout выше дефолтных 5м: разбор вложенных jar внутри Spring Boot fat-jar
# (javadb) на локальной машине идёт заметно дольше, чем в CI.
# trivy-cache — именованный том на /root/.cache/trivy (дефолтный cache-dir):
# без него кэш БД жил бы в контейнере, удаляемом --rm, и trivy-db/javadb
# (940 MiB) качались бы при каждом прогоне. С томом перекачка только по
# истечении срока жизни БД: trivy-db ~24ч, javadb 3 дня.
check-image-vuln: ci-image ## trivy: уязвимости образа CRITICAL/HIGH с исправлением (как в CI)
	docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
	  -v trivy-cache:/root/.cache/trivy \
	  aquasec/trivy:0.58.2 image --timeout 15m --exit-code 1 --severity CRITICAL,HIGH --ignore-unfixed $(CI_IMAGE)

check-ci: check-secrets check-dockerfile check-image-vuln ## Все CI-проверки локально: gitleaks + hadolint + trivy

## --- Нагрузочное тестирование (k6, scripts/load/) ---
# Прогон сидирует пул пользователей через /api/v1/auth/register — это
# внутренний JWT-контур, поэтому dev-up-load переводит приложение на профиль dev.

dev-up-load: env ## Поднять стек для нагрузки (профиль dev и TRUST_PROXY_HEADERS=true инлайном; только локально)
	# Внутренний JWT-контур (пул сидируется через /api/v1/auth/register) и
	# доверие X-Forwarded-For (каждой VU — свой bucket рейт-лимита) задаются
	# инлайном: .env не меняется.
	SPRING_PROFILES_ACTIVE=dev \
	TRUST_PROXY_HEADERS=true OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces \
	$(COMPOSE_LOAD) $(PROFILES_LOCAL) up --build -d --wait

load: dev-up-load ## Нагрузочный user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
	bash scripts/load/run.sh

load-smoke: dev-up-load ## Короткий smoke-прогон user journey (2 VU, 1 минута)
	TARGET_VUS=2 DURATION=1m bash scripts/load/run.sh

## --- Миграции схемы (Liquibase с хоста; порт 127.0.0.1:5432 публикует
## docker-compose.override.yaml — нужен make dev-up-deps) ---
# Миграции также применяет само приложение при старте (DB_MIGRATIONS_ENABLED).
# Цели ниже — для чистого/ручного прогона без контейнера приложения.

# Контекст передаёт и dev-migrate: Liquibase без -Dliquibase.contexts применяет
# ВСЕ changeset'ы, включая помеченные context="seed" — без него «чистая»
# миграция незаметно наполнила бы базу фикстурами.
dev-migrate: env ## Миграции Liquibase на основную БД (localhost:5432)
	@set -a && . infrastructure/.env && set +a && \
	$(MVN) -q liquibase:update \
	  -Dliquibase.url=jdbc:postgresql://127.0.0.1:5432/desk \
	  -Dliquibase.username=user \
	  -Dliquibase.password="$$DB_PASSWORD" \
	  -Dliquibase.contexts="$${DB_MIGRATIONS_CONTEXTS:-schema}"

# Сид отличается от dev-migrate контекстом: фикстуры помечены context="seed" и не
# применяются при DB_MIGRATIONS_CONTEXTS=schema (чистая dev-база по умолчанию).
# Повторный прогон безопасен — seed-INSERT'ы идемпотентны. Чистая схема взамен:
# make dev-down-volumes && make dev-up.
dev-db-seed: env ## Локальные фикстуры (Liquibase context=seed) на основную БД (localhost:5432)
	@set -a && . infrastructure/.env && set +a && \
	$(MVN) -q liquibase:update \
	  -Dliquibase.url=jdbc:postgresql://127.0.0.1:5432/desk \
	  -Dliquibase.username=user \
	  -Dliquibase.password="$$DB_PASSWORD" \
	  -Dliquibase.contexts=schema,seed

## --- OpenAPI (нужен поднятый стек: make dev-up) ---

dev-openapi: ## Забрать OpenAPI-спеку с запущенного стека в openapi.json
	curl -sk https://localhost:8443/v3/api-docs -o openapi.json && echo "openapi.json обновлён"

dev-openapi-types: dev-openapi ## Сгенерировать TypeScript-типы из спеки (нужен npx)
	npx --yes openapi-typescript openapi.json -o web/src/api/schema.d.ts

## --- E2E (нужен поднятый стек: make dev-up) ---
# E2e разделены на два контура аутентификации:
#
# 1. e2e-jwt-in-app — внутренний JWT-контур (профиль dev).
#    Сценарии ходят в cookie-эндпоинты /api/v1/auth/*, проверяют
#    refresh-ротацию, логаут, смену пароля, email-подтверждения.
#
# 2. e2e-keycloak — внешний OAuth2-контур (профиль dev,keycloak).
#    Использует Bearer-токены Keycloak (direct access grant), не зависит
#    от cookie и локального AuthController (не создаётся в keycloak-профиле).
#    Owner-transfer не проверяется — требует пароль пользователя,
#    у Keycloak-пользователей password_hash = 'keycloak-no-local-password:uuid'.
#
# Перед сценариями сбрасываются in-memory бакеты рейт-лимитов
# (POST /api/v1/dev/ratelimit-reset, @Profile("dev")) — без make dev-restart.
#
# e2e-ratelimit в e2e-all не входит: логика бакетов/429/Retry-After целиком
# покрыта RateLimitServiceTest, а e2e-прогон тратит 56 последовательных
# запросов и всегда идёт последним по лимитам. Отдельная цель ниже — для
# ручного прогона после изменений в рейт-лимитах.

e2e-reset-limits: ## Сбросить rate-limit бакеты (dev-эндпоинт; стек запущен, профиль dev)
	@code=$$(curl -sk -o /dev/null -w '%{http_code}' -X POST \
	  -H 'X-Requested-With: XMLHttpRequest' \
	  https://localhost:8443/api/v1/dev/ratelimit-reset); \
	if [ "$$code" = "204" ] || [ "$$code" = "200" ]; then \
	  echo "rate-limit бакеты сброшены"; \
	else \
	  echo "предупреждение: сброс лимитов не прошёл (HTTP $$code) — стек запущен? профиль dev?"; \
	fi

## --- JWT in-app-контур (профиль dev) ---

e2e-portal-access: dev-profile-local e2e-reset-limits ## E2E: права на портал
	bash $(E2E_DIR)/e2e-portal-access.sh

e2e-ratelimit: dev-profile-local e2e-reset-limits ## E2E: рейт-лимиты
	bash $(E2E_DIR)/e2e-ratelimit.sh

e2e-jwt: dev-profile-local e2e-reset-limits ## E2E: JWT-поток аутентификации
	bash $(E2E_DIR)/e2e-jwt-flow.sh

e2e-notifications: dev-profile-local e2e-reset-limits ## E2E: in-app оповещения (long polling)
	bash $(E2E_DIR)/e2e-notifications.sh

e2e-portal-transfer: dev-profile-local e2e-reset-limits ## E2E: передача владения порталом
	bash $(E2E_DIR)/e2e-portal-transfer.sh

e2e-token-cleanup: dev-profile-local e2e-reset-limits ## E2E: очистка refresh-токенов по cron (пересоздаёт app)
	bash $(E2E_DIR)/e2e-token-cleanup.sh

## --- Keycloak-контур (профиль dev,keycloak) ---

e2e-keycloak-auth: dev-profile-keycloak e2e-reset-limits ## E2E: вход через Keycloak (OAuth2 RS, роли, блокировка)
	bash $(E2E_DIR)/e2e-keycloak.sh

e2e-portal-access-kc: dev-profile-keycloak e2e-reset-limits ## E2E: портал через Keycloak (Bearer, CRUD)
	bash $(E2E_DIR)/e2e-portal-access-kc.sh

e2e-notifications-kc: dev-profile-keycloak e2e-reset-limits ## E2E: уведомления через Keycloak (Bearer)
	bash $(E2E_DIR)/e2e-notifications-kc.sh

## --- Агрегаторы контуров ---
# Профиль переключается зависимостью цели, а не инлайном в рецепте: при прямом
# вызове make e2e-jwt-in-app приложение гарантированно оказывается в нужном
# контуре, иначе сценарии ключевого контура упали бы на профиле предыдущего
# прогона.
#
# e2e-all намеренно включает ТОЛЬКО Keycloak-контур: это рекомендуемая схема
# развёртывания, и именно она проверяется по умолчанию. Внутренний JWT-контур
# (e2e-jwt-in-app) поддерживается для автономных инсталляций без внешнего
# OAuth2-сервера, но новым пользователям не предлагается — поэтому его сценарии
# вынесены в отдельную цель и в e2e-all не входят.

e2e-jwt-in-app: dev-profile-local ## E2E: контур внутреннего JWT (cookie, профиль dev; не входит в e2e-all)
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-portal-access.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-jwt-flow.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-notifications.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-portal-transfer.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-token-cleanup.sh

e2e-keycloak: dev-profile-keycloak ## E2E: контур Keycloak (Bearer, профиль dev,keycloak)
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-keycloak.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-portal-access-kc.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-notifications-kc.sh

e2e-all: e2e-keycloak ## E2E: рекомендуемый контур (Keycloak); внутренний JWT — отдельной целью e2e-jwt-in-app

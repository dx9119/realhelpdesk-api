# RealHelpDesk — быстрые команды разработки.
# Запуск: make help

MVN ?= mvn
IMAGE ?= realhelpdesk:latest
APP_JAR ?= target/realhelpdesk-0.0.1-SNAPSHOT.jar
E2E_DIR ?= scripts

# Ревизия git для LABEL образа (org.opencontainers.image.revision)
GIT_SHA ?= $(shell git rev-parse HEAD 2>/dev/null || echo unknown)

# Единый compose-файл + профили (infra/dev/app/monitoring/load — см. шапку
# infrastructure/docker-compose.yaml). Наборы профилей передаются явно:
# compose не сам включает профили зависимостей (depends_on на сервис с
# непривязанным профилем — ошибка валидации).
COMPOSE ?= docker compose -f infrastructure/docker-compose.yaml
PROFILES_DEV ?= --profile infra --profile dev --profile app
PROFILES_PROD ?= --profile infra --profile app
PROFILES_OBS ?= $(PROFILES_DEV) --profile monitoring
PROFILES_ALL ?= --profile infra --profile dev --profile app --profile monitoring --profile load

.DEFAULT_GOAL := help

.PHONY: help build clean compile package test verify format check run \
        dev-run dev-test \
        env apply-config docker-build up up-prod up-load down down-volumes logs ps restart shell check-image \
        profile-local profile-keycloak \
        obs-up obs-down obs-open obs-reload obs-alerts \
        load load-smoke \
        migrate \
        openapi openapi-types \
        e2e-portal-access e2e-ratelimit e2e-jwt e2e-notifications e2e-portal-transfer e2e-token-cleanup e2e-keycloak e2e-all

help: ## Показать список команд
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

## --- Сборка и тесты (Maven, на хосте) ---

build: ## Собрать jar без тестов (package -DskipTests)
	$(MVN) -DskipTests package

clean: ## Очистить target/
	$(MVN) clean

compile: ## Компиляция main и test
	$(MVN) compile test-compile

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

dev-run: env ## Dev-среда: compose up в foreground (логи всех сервисов, Ctrl+C — стоп)
	$(COMPOSE) $(PROFILES_DEV) up --build

dev-test: ## Dev-среда: mvn verify + e2e-сценарии (нужен поднятый стек: dev-run/up)
	$(MVN) verify
	$(MAKE) e2e-all

## --- Docker ---

env: ## Синхронизировать infrastructure/.env с шаблоном (создать, дописать/обновить ключи)
	bash scripts/docker/sync-env.sh

apply-config: ## Быстро применить конфиги/лимиты к запущенному стеку: sync .env + пересоздать app без сборки
	bash scripts/docker/sync-env.sh
	$(COMPOSE) $(PROFILES_ALL) up -d --force-recreate --no-deps app

docker-build: ## Собрать docker-образ приложения (с меткой GIT_SHA)
	$(COMPOSE) $(PROFILES_DEV) build --build-arg GIT_SHA=$(GIT_SHA)

up: env ## Поднять локальный стек (профили infra+dev+app, сборка образа, в фоне)
	$(COMPOSE) $(PROFILES_DEV) up --build -d

up-prod: env ## Поднять рабочий стек (профили infra+app; настройки — секция «Рабочий контур» в .env)
	$(COMPOSE) $(PROFILES_PROD) up --build -d

down: ## Остановить стек (все профили)
	$(COMPOSE) $(PROFILES_ALL) down

down-volumes: ## Остановить стек и удалить тома (данные БД/MinIO)
	$(COMPOSE) $(PROFILES_ALL) down -v

## --- Наблюдаемость (подробности: docs/observability/overview.md) ---

obs-up: env ## Поднять стек + наблюдаемость (профили +monitoring; Grafana :3001, Mimir :9090)
	OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces $(COMPOSE) $(PROFILES_OBS) up --build -d --remove-orphans

obs-down: ## Остановить стек с наблюдаемостью (все профили, томы целы)
	$(COMPOSE) $(PROFILES_ALL) down

obs-open: ## Открыть Grafana в браузере (нужен obs-up; порт GRAFANA_PORT, дефолт 3001)
	@xdg-open http://localhost:3001 >/dev/null 2>&1 || open http://localhost:3001 >/dev/null 2>&1 || echo "Grafana: http://localhost:3001"

obs-reload: ## Перечитать правила алертов Mimir (нужен obs-up; mimir/rules/anonymous/realhelpdesk.yml)
	$(COMPOSE) $(PROFILES_OBS) restart mimir && echo "Mimir перезапущен — правила перечитаны"

obs-alerts: ## Текущие состояния алертов Mimir (нужен obs-up)
	curl -s localhost:9090/prometheus/api/v1/alerts | grep -oE '"alertname":"[^"]+"|"state":"[^"]+"' | paste - - || echo "Mimir недоступен"

logs: ## Логи приложения (follow)
	$(COMPOSE) $(PROFILES_ALL) logs -f app

ps: ## Статус контейнеров (все профили)
	$(COMPOSE) $(PROFILES_ALL) ps

restart: ## Перезапустить только приложение
	$(COMPOSE) $(PROFILES_ALL) restart app

profile-local: ## Переключить приложение на внутренний JWT-контур (профиль dev) и пересоздать app
	bash scripts/docker/set-profile.sh dev

profile-keycloak: ## Переключить приложение на контур Keycloak (профиль dev,keycloak) и пересоздать app
	bash scripts/docker/set-profile.sh dev,keycloak

shell: ## Shell внутри контейнера app
	$(COMPOSE) $(PROFILES_ALL) exec app sh

check-image: docker-build ## Проверки образа: секреты, ФС, jar
	./scripts/docker/check-image.sh $(IMAGE)

## --- Нагрузочное тестирование (k6, scripts/load/) ---
# Прогон сидирует пул пользователей через /api/v1/auth/register — это
# внутренний JWT-контур, поэтому up-load переводит приложение на профиль dev.

up-load: env ## Поднять стек для нагрузки (TRUST_PROXY_HEADERS=true инлайном; только локально)
	# Внутренний JWT-контур: пул сидируется через /api/v1/auth/register.
	# NO_RESTART=1 — app пересоздаст сам up ниже, лишний перезапуск не нужен.
	NO_RESTART=1 bash scripts/docker/set-profile.sh dev
	TRUST_PROXY_HEADERS=true OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces \
	$(COMPOSE) $(PROFILES_DEV) up --build -d

load: up-load ## Нагрузочный user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
	bash scripts/load/run.sh

load-smoke: up-load ## Короткий smoke-прогон user journey (2 VU, 1 минута)
	TARGET_VUS=2 DURATION=1m bash scripts/load/run.sh

## --- Миграции схемы (Liquibase с хоста; порт 127.0.0.1:5432 публикует db-gateway) ---
# Миграции также применяет само приложение при старте (DB_MIGRATIONS_ENABLED).
# Цели ниже — для чистого/ручного прогона без контейнера приложения.

migrate: env ## Миграции Liquibase на основную БД (localhost:5432)
	@set -a && . infrastructure/.env && set +a && \
	$(MVN) -q liquibase:update \
	  -Dliquibase.url=jdbc:postgresql://127.0.0.1:5432/desk \
	  -Dliquibase.username=user \
	  -Dliquibase.password="$$DB_PASSWORD"

## --- OpenAPI (нужен поднятый стек: make up) ---

openapi: ## Забрать OpenAPI-спеку с запущенного стека в openapi.json
	curl -sk https://localhost:8443/v3/api-docs -o openapi.json && echo "openapi.json обновлён"

openapi-types: openapi ## Сгенерировать TypeScript-типы из спеки (нужен npx)
	npx --yes openapi-typescript openapi.json -o web/src/api/schema.d.ts

## --- E2E (нужен поднятый стек: make up) ---
# Сценарии ходят в cookie-эндпоинты /api/v1/auth/* — это внутренний JWT-контур,
# поэтому перед прогоном приложение переводится на профиль dev (без keycloak).
# Контур Keycloak проверяет отдельный сценарий e2e-keycloak (профиль dev,keycloak).

e2e-portal-access: profile-local ## E2E: права на портал
	bash $(E2E_DIR)/e2e-portal-access.sh

e2e-ratelimit: profile-local ## E2E: рейт-лимиты
	bash $(E2E_DIR)/e2e-ratelimit.sh

e2e-jwt: profile-local ## E2E: JWT-поток аутентификации
	bash $(E2E_DIR)/e2e-jwt-flow.sh

e2e-notifications: profile-local ## E2E: in-app оповещения (long polling)
	bash $(E2E_DIR)/e2e-notifications.sh

e2e-portal-transfer: profile-local ## E2E: передача владения порталом
	bash $(E2E_DIR)/e2e-portal-transfer.sh

e2e-token-cleanup: profile-local ## E2E: очистка отозванных и протухших refresh-токенов (пересоздаёт app)
	bash $(E2E_DIR)/e2e-token-cleanup.sh

e2e-keycloak: profile-keycloak ## E2E: вход через Keycloak (нужен сервис keycloak профиля dev)
	bash $(E2E_DIR)/e2e-keycloak.sh

e2e-all: profile-local ## E2E: все сценарии подряд (тратит рейт-лимиты!)
	bash $(E2E_DIR)/e2e-portal-access.sh
	bash $(E2E_DIR)/e2e-ratelimit.sh
	bash $(E2E_DIR)/e2e-jwt-flow.sh
	bash $(E2E_DIR)/e2e-notifications.sh
	bash $(E2E_DIR)/e2e-portal-transfer.sh
	bash $(E2E_DIR)/e2e-token-cleanup.sh
	$(MAKE) e2e-keycloak

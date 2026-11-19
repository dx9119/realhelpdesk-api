# RealHelpDesk — быстрые команды разработки.
# Запуск: make help
#
# Проект запускается только локально: приложение живёт на хосте
# (make run-local / запуск из IDE), в Docker поднимается лишь инфраструктура
# и наблюдаемость.

MVN ?= ./mvnw
E2E_DIR ?= scripts

# Compose-файлы: base — ядро (postgres, minio, локальные инструменты),
# obs — наблюдаемость (mimir, tempo, otel-collector, alloy),
# load — k6. Цепочки подключают docker-compose.override.yaml: он публикует
# порты БД/MinIO на loopback хоста (для IDEA/psql/MinIO-консоли/приложения).
COMPOSE ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.override.yaml
# Цепочка с наблюдаемостью: dev-run/dev-down поднимают и гасят monitoring-сервисы
# (mimir/tempo/otel-collector/alloy), они объявлены только в obs-файле.
COMPOSE_OBS ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.obs.yaml -f infrastructure/docker-compose.override.yaml
COMPOSE_LOAD ?= docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.load.yaml -f infrastructure/docker-compose.override.yaml
PROFILES_DEPS ?= --profile infra --profile local-tools
PROFILES_OBS ?= $(PROFILES_DEPS) --profile monitoring
PROFILES_LOAD ?= $(PROFILES_DEPS) --profile load

# Параметры образа: GIT_SHA попадает в LABEL org.opencontainers.image.revision,
# поэтому берётся из HEAD, а не оставляется unknown.
IMAGE_NAME ?= realhelpdesk
IMAGE_TAG ?= latest
GIT_SHA := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)

.DEFAULT_GOAL := help

.PHONY: help build image clean test verify run-local \
        dev-run dev-down dev-down--del-volumes \
        env dev-openapi dev-openapi-types \
        check-ci-all check-code-style check-secrets \
        load-smoke load-full .load-up \
        e2e-reset-limits \
        e2e-ratelimit \
        e2e-keycloak-auth e2e-portal-access-kc e2e-notifications-kc \
        e2e-keycloak e2e-all \
        require-app

help: ## Показать список команд
	@grep -hE '^[a-zA-Z0-9_.-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'

## --- Приложение на хосте ---

# Контур задаёт RUN_PROFILES (scripts/dev/run-local.sh добавляет к нему host —
# loopback-адреса инфры). Инфраструктуру поднимает make dev-run, либо
# spring-boot-docker-compose сам при старте с профилем host.
run-local: env ## Приложение на хосте (профили dev,host); токены выпускает Keycloak
	bash scripts/dev/run-local.sh

## --- Сборка и тесты (Maven, на хосте) ---

build: ## Собрать jar с юнит-тестами (package; интеграционные *IT не входят)
	$(MVN) -DskipITs package

# Только сборка образа: приложение в Docker не поднимается (см. шапку файла).
# BuildKit-кэш ~/.m2 из Dockerfile требует DOCKER_BUILDKIT=1 — в Docker 29 включён по умолчанию.
image: ## Собрать контейнерный образ (IMAGE_NAME/IMAGE_TAG, без запуска и пуша)
	docker build --build-arg GIT_SHA=$(GIT_SHA) -t $(IMAGE_NAME):$(IMAGE_TAG) .

clean: ## Очистить target/
	$(MVN) clean

test: ## Юнит-тесты (surefire)
	$(MVN) test

verify: ## Тесты + интеграционные *IT (failsafe)
	$(MVN) verify

check-code-style: ## Проверки стиля (spotless:check + checkstyle)
	$(MVN) spotless:check checkstyle:check

## --- Инфраструктура (Docker, без приложения) ---

env: ## Создать infrastructure/.env и сгенерировать пустые локальные секреты
	bash scripts/docker/init-secrets.sh

dev-run: env ## Dev-среда: инфраструктура + наблюдаемость в фоне (БД, MinIO, smtp4dev, Keycloak, Mimir/Tempo/OTel)
	$(COMPOSE_OBS) $(PROFILES_OBS) up -d --wait

# COMPOSE_OBS, а не COMPOSE: dev-run поднимает monitoring, а down останавливает
# только сервисы из переданной цепочки -f — без obs-файла контейнеры
# наблюдаемости остались бы висеть (тома obs_* — тоже).
dev-down: ## Остановить dev-контур (инфраструктура + наблюдаемость)
	$(COMPOSE_OBS) $(PROFILES_OBS) down --remove-orphans

dev-down--del-volumes: ## Остановить dev-контур и удалить тома (данные БД/MinIO + метрики/трейсы/логи)
	$(COMPOSE_OBS) $(PROFILES_OBS) down -v --remove-orphans

## --- Проверки перед коммитом (те же, что job docker в .github/workflows/ci.yml) ---

check-secrets: ## gitleaks: секреты в дереве и истории коммитов (как в CI)
	docker run --rm -v "$(CURDIR):/repo" zricethezav/gitleaks:v8.21.2 detect --source /repo --redact

check-ci-all: check-secrets ## Все быстрые CI-проверки локально: gitleaks

## --- OpenAPI (нужно запущенное приложение на localhost:8080) ---

dev-openapi: ## Забрать OpenAPI-спеку с запущенного приложения в openapi.json
	curl -s http://localhost:8080/v3/api-docs -o openapi.json && echo "openapi.json обновлён"

dev-openapi-types: dev-openapi ## Сгенерировать TypeScript-типы из спеки (нужен npx)
	npx --yes openapi-typescript openapi.json -o web/src/api/schema.d.ts

## --- Нагрузочное тестирование (k6, scripts/load/) ---
# Приложение — на хосте (профиль dev: пул сидируется через /api/v1/auth/register),
# в контейнер поднимается только k6 и смотрит на хост через host.docker.internal.
# TRUST_PROXY_HEADERS=true задаётся инлайном в запуске приложения — доверие
# X-Forwarded-For даёт каждой VU свой bucket рейт-лимита.

# Служебная (не в help): k6-стек без app — профиль load + инфраструктура.
.load-up: env
	$(COMPOSE_LOAD) $(PROFILES_LOAD) up -d --no-build

load-full: .load-up ## Нагрузочный user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
	bash scripts/load/run.sh

load-smoke: .load-up ## Короткий smoke-прогон user journey (2 VU, 1 минута)
	TARGET_VUS=2 DURATION=1m bash scripts/load/run.sh

## --- E2E (нужно запущенное приложение на localhost:8080) ---
# Контур аутентификации один: токены выпускает Keycloak (direct access grant),
# приложение проверяет их как OAuth2 Resource Server. Локального
# /api/v1/auth/* в приложении нет.
#
# Перед сценариями сбрасываются in-memory бакеты рейт-лимитов
# (POST /api/v1/dev/ratelimit-reset, @Profile("dev")) — без перезапуска app.

e2e-reset-limits: ## Сбросить rate-limit бакеты (dev-эндпоинт; приложение запущено)
	@code=$$(curl -s -o /dev/null -w '%{http_code}' -X POST \
	  -H 'X-Requested-With: XMLHttpRequest' \
	  http://localhost:8080/api/v1/dev/ratelimit-reset); \
	if [ "$$code" = "204" ] || [ "$$code" = "200" ]; then \
	  echo "rate-limit бакеты сброшены"; \
	else \
	  echo "предупреждение: сброс лимитов не прошёл (HTTP $$code) — приложение запущено? профиль dev?"; \
	fi

## --- Проверка доступности перед e2e ---
# e2e-цели не управляют приложением: они лишь убеждаются, что оно доступно
# на localhost:8080 (make run-local поднимает приложение на хосте).

require-app:
	@code=$$(curl -sk -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/health); \
	if [ "$$code" != "200" ]; then \
	  echo "ошибка: приложение недоступно на http://localhost:8080 — поднимите инфраструктуру (make dev-run) и запустите приложение на хосте (make run-local)"; \
	  exit 1; \
	fi; \
	echo "приложение доступно на http://localhost:8080"

## --- Keycloak-контур ---

e2e-ratelimit: require-app e2e-reset-limits ## E2E: рейт-лимиты
	bash $(E2E_DIR)/e2e-ratelimit.sh

e2e-keycloak-auth: require-app e2e-reset-limits ## E2E: вход через Keycloak (OAuth2 RS, роли, блокировка)
	bash $(E2E_DIR)/e2e-keycloak.sh

e2e-portal-access-kc: require-app e2e-reset-limits ## E2E: портал через Keycloak (Bearer, CRUD)
	bash $(E2E_DIR)/e2e-portal-access-kc.sh

e2e-notifications-kc: require-app e2e-reset-limits ## E2E: уведомления через Keycloak (Bearer)
	bash $(E2E_DIR)/e2e-notifications-kc.sh

## --- Агрегатор ---
# e2e-цели чисто тестовые: контейнеры не поднимают и не пересобирают, только
# проверяют доступность приложения и запускают сценарии.

e2e-keycloak: require-app ## E2E: контур Keycloak (Bearer)
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-keycloak.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-portal-access-kc.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-notifications-kc.sh

e2e-all: e2e-keycloak ## E2E: все сценарии Keycloak-контура

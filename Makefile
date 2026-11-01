# RealHelpDesk — быстрые команды разработки.
# Запуск: make help

MVN ?= ./mvnw
IMAGE ?= realhelpdesk:latest
# Образ для проверок CI (hadolint/trivy job в .github/workflows/ci.yml)
CI_IMAGE ?= realhelpdesk:ci
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
.PHONY: help build clean test verify run-local \
        dev-run \
        env dev-apply-config dev-docker-build prod-up dev-down dev-down--del-volumes \
        dev-logs dev-ps dev-restart dev-shell check-image \
        check-ci-all check-code-style check-secrets check-dockerfile ci-image check-image-vuln \
        prod-backup prod-restore \
        dev-profile-local dev-profile-keycloak \
        obs-up obs-down obs-reload obs-alerts \
        load-full load-smoke .load-up \
        dev-openapi dev-openapi-types \
        e2e-reset-limits \
        e2e-portal-access e2e-ratelimit e2e-jwt e2e-notifications e2e-portal-transfer e2e-token-cleanup e2e-keycloak \
        e2e-portal-access-kc e2e-notifications-kc \
        e2e-jwt-in-app e2e-keycloak e2e-all \
        require-local require-keycloak

help: ## Показать список команд
	@grep -hE '^[a-zA-Z0-9_.-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'

## --- Сборка и тесты (Maven, на хосте) ---

build: ## Собрать jar с юнит-тестами (package; интеграционные *IT не входят)
	$(MVN) -DskipITs package

clean: ## Очистить target/
	$(MVN) clean

test: ## Юнит-тесты (surefire)
	$(MVN) test

verify: ## Тесты + интеграционные *IT (failsafe)
	$(MVN) verify

check-code-style: ## Проверки стиля (spotless:check + checkstyle)
	$(MVN) spotless:check checkstyle:check

run-local: env ## Приложение на хосте против dockerized-инфры (профили dev,host; инфру поднимает spring-boot-docker-compose или make dev-run)
	bash scripts/dev/run-local.sh

# Dev-режим: приложение запускается из IDE/CLI на хосте, поэтому контейнер app
# и сборка его образа не участвуют — только инфраструктура (infra+local-tools).
dev-run: env ## Dev-среда: compose up инфраструктуры в foreground (БД, MinIO, smtp4dev, Keycloak; Ctrl+C — стоп)
	$(COMPOSE) $(PROFILES_DEPS) up

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

prod-up: env ## Поднять рабочий стек (infra+app, pull из ghcr.io; настройки — «Рабочий контур» в .env)
	$(COMPOSE_PROD) $(PROFILES_PROD) up -d --no-build

dev-down: ## Остановить стек (все профили)
	$(COMPOSE) $(PROFILES_ALL) down

dev-down--del-volumes: ## Остановить стек и удалить тома (данные БД/MinIO)
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

check-ci-all: check-secrets check-dockerfile check-image-vuln ## Все CI-проверки локально: gitleaks + hadolint + trivy

## --- Нагрузочное тестирование (k6, scripts/load/) ---
# Прогон сидирует пул пользователей через /api/v1/auth/register — это
# внутренний JWT-контур, поэтому стек для нагрузки переводит приложение на профиль dev.

# Служебная (не в help): профиль dev и TRUST_PROXY_HEADERS=true инлайном — .env не меняется;
# доверие X-Forwarded-For даёт каждой VU свой bucket рейт-лимита.
.load-up: env
	# Внутренний JWT-контур (пул сидируется через /api/v1/auth/register) и
	# доверие X-Forwarded-For (каждой VU — свой bucket рейт-лимита) задаются
	# инлайном: .env не меняется.
	SPRING_PROFILES_ACTIVE=dev \
	TRUST_PROXY_HEADERS=true OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces \
	$(COMPOSE_LOAD) $(PROFILES_LOCAL) up --build -d --wait

load-full: .load-up ## Нагрузочный user journey: TARGET_VUS=10 DURATION=5m POOL_SIZE=8 (env)
	bash scripts/load/run.sh

load-smoke: .load-up ## Короткий smoke-прогон user journey (2 VU, 1 минута)
	TARGET_VUS=2 DURATION=1m bash scripts/load/run.sh

## --- OpenAPI (нужен поднятый стек: make dev-run) ---

dev-openapi: ## Забрать OpenAPI-спеку с запущенного стека в openapi.json
	curl -s http://localhost:8080/v3/api-docs -o openapi.json && echo "openapi.json обновлён"

dev-openapi-types: dev-openapi ## Сгенерировать TypeScript-типы из спеки (нужен npx)
	npx --yes openapi-typescript openapi.json -o web/src/api/schema.d.ts

## --- E2E (нужен запущенный стек: make dev-run + приложение из IDE) ---
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
	@code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
	  -H 'X-Requested-With: XMLHttpRequest' \
	  http://localhost:8080/api/v1/dev/ratelimit-reset); \
	if [ "$$code" = "204" ] || [ "$$code" = "200" ]; then \
	  echo "rate-limit бакеты сброшены"; \
	else \
	  echo "предупреждение: сброс лимитов не прошёл (HTTP $$code) — стек запущен? профиль dev?"; \
	fi

## --- Проверки контура перед e2e ---
# e2e-цели не управляют стеком: они лишь убеждаются, что приложение доступно и
# работает на нужном контуре. Профиль переключается явно до прогона
# (dev-profile-local / dev-profile-keycloak) — иначе e2e-цель пересоздавала бы
# контейнеры и перетягивала за собой весь стек.

# Контур читается из запущенного контейнера app (именно его пересоздают
# dev-profile-*). Приложение, запущенное с хоста (run-local), контейнера не
# имеет — тогда остаётся только проверка доступности.
require-profile:
	@code=$$(curl -sk -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/health); \
	if [ $$code != 200 ]; then \
	  echo "ошибка: приложение недоступно на http://localhost:8080 — поднимите инфраструктуру (make dev-run) и запустите приложение из IDE"; \
	  exit 1; \
	fi; \
	profile=$$($(COMPOSE) $(PROFILES_ALL) exec -T app printenv SPRING_PROFILES_ACTIVE 2>/dev/null | tr -d '\r'); \
	if [ -n "$$profile" ] && [ "$$profile" != "$(PROFILE_REQUIRED)" ]; then \
	  echo "ошибка: приложение на контуре '$$profile', требуется '$(PROFILE_REQUIRED)' — выполните make $(PROFILE_SWITCH)"; \
	  exit 1; \
	fi; \
	echo "контур готов: $${profile:-приложение с хоста, профиль не проверяется}"

require-local: ## Проверить готовность JWT-контура (профиль dev) без пересоздания стека
	@$(MAKE) --no-print-directory require-profile PROFILE_REQUIRED=dev PROFILE_SWITCH=dev-profile-local

require-keycloak: ## Проверить готовность Keycloak-контура (профиль dev,keycloak) без пересоздания стека
	@$(MAKE) --no-print-directory require-profile PROFILE_REQUIRED=dev,keycloak PROFILE_SWITCH=dev-profile-keycloak

## --- JWT in-app-контур (профиль dev) ---

e2e-portal-access: require-local e2e-reset-limits ## E2E: права на портал
	bash $(E2E_DIR)/e2e-portal-access.sh

e2e-ratelimit: require-local e2e-reset-limits ## E2E: рейт-лимиты
	bash $(E2E_DIR)/e2e-ratelimit.sh

e2e-jwt: require-local e2e-reset-limits ## E2E: JWT-поток аутентификации
	bash $(E2E_DIR)/e2e-jwt-flow.sh

e2e-notifications: require-local e2e-reset-limits ## E2E: in-app оповещения (long polling)
	bash $(E2E_DIR)/e2e-notifications.sh

e2e-portal-transfer: require-local e2e-reset-limits ## E2E: передача владения порталом
	bash $(E2E_DIR)/e2e-portal-transfer.sh

e2e-token-cleanup: require-local e2e-reset-limits ## E2E: очистка refresh-токенов по cron (пересоздаёт app)
	bash $(E2E_DIR)/e2e-token-cleanup.sh

## --- Keycloak-контур (профиль dev,keycloak) ---

e2e-keycloak-auth: require-keycloak e2e-reset-limits ## E2E: вход через Keycloak (OAuth2 RS, роли, блокировка)
	bash $(E2E_DIR)/e2e-keycloak.sh

e2e-portal-access-kc: require-keycloak e2e-reset-limits ## E2E: портал через Keycloak (Bearer, CRUD)
	bash $(E2E_DIR)/e2e-portal-access-kc.sh

e2e-notifications-kc: require-keycloak e2e-reset-limits ## E2E: уведомления через Keycloak (Bearer)
	bash $(E2E_DIR)/e2e-notifications-kc.sh

## --- Агрегаторы контуров ---
# e2e-цели стали чисто тестовыми: они не поднимают и не пересобирают контейнеры,
# только проверяют доступность стека (require-local / require-keycloak) и
# запускают сценарии. Профиль переключается до вызова e2e явно:
#   make dev-profile-local    # JWT-контур
#   make dev-profile-keycloak # Keycloak-контур
#
# e2e-all намеренно включает ТОЛЬКО Keycloak-контур: это рекомендуемая схема
# развёртывания, и именно она проверяется по умолчанию. Внутренний JWT-контур
# (e2e-jwt-in-app) поддерживается для автономных инсталляций без внешнего
# OAuth2-сервера, но новым пользователям не предлагается — поэтому его сценарии
# вынесены в отдельную цель и в e2e-all не входят.

e2e-jwt-in-app: require-local ## E2E: контур внутреннего JWT (cookie, профиль dev; не входит в e2e-all)
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

e2e-keycloak: require-keycloak ## E2E: контур Keycloak (Bearer, профиль dev,keycloak)
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-keycloak.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-portal-access-kc.sh
	$(MAKE) e2e-reset-limits
	bash $(E2E_DIR)/e2e-notifications-kc.sh

e2e-all: e2e-keycloak ## E2E: рекомендуемый контур (Keycloak); внутренний JWT — отдельной целью e2e-jwt-in-app

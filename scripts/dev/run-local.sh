#!/usr/bin/env bash
# Запуск приложения на хосте против dockerized-инфраструктуры (make run-local).
#
# Предпосылка: make dev-run — postgres/minio/smtp/keycloak подняты, порты
# 5432/9000/25/8180 проброшены на 127.0.0.1 (docker-compose.override.yaml).
# Если стек не поднят, профиль host поднимет его сам при старте
# (spring-boot-docker-compose, start-only).
#
# Токены выпускает Keycloak: профиль приложения один — dev, к нему всегда
# добавляется host (loopback-адреса инфры из application-host.properties).
set -euo pipefail
cd "$(dirname "$0")/../.."

# shellcheck source=./load-env.sh
source scripts/dev/load-env.sh

# После load-env.sh: .env может содержать SPRING_PROFILES_ACTIVE (его экспортирует
# цикл загрузки), а host обязателен — в нём loopback-адреса инфраструктуры.
export SPRING_PROFILES_ACTIVE="dev,host"

# --- JDK: если задан JAVA_HOME (например, JDK 25), используем его ---
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME=$JAVA_HOME"
fi

echo "Старт: profiles=$SPRING_PROFILES_ACTIVE, HTTP (порт 8080)"
exec ./mvnw -q spring-boot:run

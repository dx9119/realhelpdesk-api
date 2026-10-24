#!/usr/bin/env bash
# Запуск приложения на хосте против dockerized-инфраструктуры (make run-local).
#
# Предпосылка: make dev-deps — postgres/minio/smtp/keycloak подняты, порты
# 5432/9000/25/8180 проброшены на 127.0.0.1 (docker-compose.override.yaml).
# Если стек не поднят, профиль host поднимет его сам при старте
# (spring-boot-docker-compose, start-only).
#
# Что делает:
#   1. подгружает infrastructure/.env — переименований ключей больше нет
#      (DB_PASSWORD/MINIO_ROOT_* читаются приложением напрямую);
#   2. выключает SSL (SSL_ENABLED=false): HTTP без keystore на хосту;
#      HTTPS остаётся в контейнерном стеке (make dev-up);
#   3. запускает ./mvnw spring-boot:run с профилями dev,host
#      (host — loopback-адреса инфры: application-host.properties).
set -euo pipefail
cd "$(dirname "$0")/../.."

ENV_FILE=infrastructure/.env
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Нет $ENV_FILE — выполните: make env" >&2
  exit 1
fi

# infrastructure/.env — не shell-скрипт: значения с пробелами (APP_NAME=Real
# help desk, STATIC_CACHE_CONTROL=private, no-store) ломают shell-источникование,
# поэтому файл загружается построчно как KEY=VALUE — так же, как читают его
# docker (env_file) и IDEA (EnvFile).
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    '' | '#'*) continue ;;
    [A-Za-z_]*=*) export "$line" ;;
  esac
done < "$ENV_FILE"

# Дефолты compose-интерполяции (:-) из infrastructure/docker-compose.yaml:
# в контейнере их подставляет compose, на хосте — этот скрипт или конфиг IDEA,
# иначе схема ${VAR} в application.properties не резолвится.
export OTLP_TRACING_ENDPOINT="${OTLP_TRACING_ENDPOINT:-http://localhost:4317}"
export TRUST_PROXY_HEADERS="${TRUST_PROXY_HEADERS:-false}"
export CLIENT_IP_FORWARDED_INDEX="${CLIENT_IP_FORWARDED_INDEX:-0}"

# MINIO_ENDPOINT из .env (http://minio:9000) перекрывает profile-файл
# (application-host.properties): OS-env имеет приоритет над application-*.properties.
# Принудительно указываем loopback, как и в host-профиле.
export MINIO_ENDPOINT="http://127.0.0.1:${MINIO_API_PORT:-9000}"

# HTTP без keystore на хосту (иначе нужен KEY_STORE_PATH с существующим p12)
export SSL_ENABLED=false
# Профили хостового запуска: dev (логи/отладка) + host (loopback-адреса инфры).
# Значение из .env (dev,keycloak) перекрывается здесь: контур keycloak из хоста
# требует KC_JWK_SET_URI на 127.0.0.1 — задайте его в env при необходимости.
export SPRING_PROFILES_ACTIVE=dev,host

# --- JDK: если задан JAVA_HOME (например, JDK 25), используем его ---
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME=$JAVA_HOME"
fi

echo "Старт: profiles=$SPRING_PROFILES_ACTIVE, SSL_ENABLED=false (HTTP)"
exec ./mvnw -q spring-boot:run

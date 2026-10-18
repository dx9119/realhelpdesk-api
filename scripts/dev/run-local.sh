#!/usr/bin/env bash
# Запуск приложения на хосте против dockerized-инфраструктуры (make run-local).
#
# Предпосылка: make up-infra — postgres/minio/db-gateway/smtp/keycloak подняты,
# порты 5432/9000/8180/25 проброшены на 127.0.0.1 через db-gateway.
#
# Что делает:
#   1. подгружает infrastructure/.env;
#   2. переименовывает ключи под схему приложения (DB_PASSWORD→DB_PASS и т.п.);
#   3. переопределяет адреса сервисов на localhost (хостовые порты db-gateway);
#   4. при отсутствии keystore генерирует самоподписанный в .cache/ssl/;
#   5. запускает ./mvnw spring-boot:run (JAVA_HOME можно задать — JDK 21).
#
# Контур аутентификации: SPRING_PROFILES_ACTIVE из .env (по умолчанию dev,keycloak).
set -euo pipefail
cd "$(dirname "$0")/../.."

ENV_FILE=infrastructure/.env
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Нет $ENV_FILE — выполните: make env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# --- Переименования под схему application.properties (см. services.app.environment) ---
export DB_PASS="${DB_PASSWORD:?DB_PASSWORD не задан — make env}"
export MINIO_ACCESS_KEY="${MINIO_ROOT_USER:?}"
export MINIO_SECRET_KEY="${MINIO_ROOT_PASSWORD:?}"

# --- Адреса на хостовые порты db-gateway / smtp / keycloak ---
export DB_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB:-desk}?reWriteBatchedInserts=true"
export MINIO_ENDPOINT="http://127.0.0.1:${MINIO_API_PORT:-9000}"
export KC_JWK_SET_URI="http://127.0.0.1:${KEYCLOAK_PORT:-8180}/realms/realhelpdesk/protocol/openid-connect/certs"
# Кейс KC_ISSUER_URI/KC_HOSTNAME остаётся из .env (localhost:8180 — уже loopback)
export MAIL_HOST=127.0.0.1

# --- Keystore: самоподписанный для локального HTTPS ---
SSL_DIR=.cache/ssl
KEYSTORE="$SSL_DIR/keystore.p12"
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$SSL_DIR"
  keytool -genkeypair -alias tomcat -keyalg RSA -keysize 2048 \
    -storetype PKCS12 -keystore "$KEYSTORE" \
    -storepass:env KEY_STORE_PASS -keypass:env KEY_STORE_PASS \
    -dname "CN=localhost" -validity 3650 2>/dev/null
  echo "Сгенерирован локальный keystore: $KEYSTORE"
fi
export KEY_STORE_PATH="file:$(pwd)/$KEYSTORE"

# --- JDK: если задан JAVA_HOME (например, JDK 21), используем его ---
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME=$JAVA_HOME"
fi

echo "Старт: profiles=${SPRING_PROFILES_ACTIVE:-<из .env>}, DB=$DB_URL, MinIO=$MINIO_ENDPOINT"
exec ./mvnw -q spring-boot:run

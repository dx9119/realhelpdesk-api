#!/usr/bin/env bash
# Запуск нагрузочного user journey (k6): сидирование пула пользователей + прогон.
#
# Требует запущенное на хосте приложение (make run-local) и поднятый k6-стек
# (его поднимают make load-full / make load-smoke). Для разделения рейт-лимитов
# между VU приложение нужно запускать с TRUST_PROXY_HEADERS=true — иначе все VU
# делят один bucket 127.0.0.1.
#
# Пользователи пула заводятся в Keycloak через Admin API: локальной регистрации
# в приложении нет, а учётная запись в БД появляется при первом запросе с
# токеном (KeycloakAuthenticationConverter, keycloak.auto-create-users).
#
# Параметры (окружение):
#   POOL_SIZE   — сколько пользователей сидировать (по умолчанию 8)
#   RUN_ID      — метка пула в email (по умолчанию unixtime; повторный запуск
#                 с тем же RUN_ID переиспользует уже сидированных пользователей)
#   TARGET_VUS  — число параллельных VU k6 (по умолчанию 10)
#   DURATION    — длительность прогона, например 5m / 30s (по умолчанию 5m)
#   THINK_TIME  — пауза между итерациями, сек (по умолчанию 1)
#   RESET_LIMITS=1 — сбросить in-memory счётчики рейт-лимитов через dev-эндпоинт
set -euo pipefail
cd "$(dirname "$0")/../.."

POOL_SIZE=${POOL_SIZE:-8}
RUN_ID=${RUN_ID:-$(date +%s)}
TARGET_VUS=${TARGET_VUS:-10}
DURATION=${DURATION:-5m}
PASSWORD=${PASSWORD:-Password123!}
BASE=${BASE:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:${KEYCLOAK_PORT:-8180}}
REALM=${REALM:-realhelpdesk}
# Приложение — на хосте, поэтому из compose-цепочки исключён профиль app:
# здесь только инфраструктура и k6.
COMPOSE="docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.load.yaml -f infrastructure/docker-compose.override.yaml --profile infra --profile local-tools --profile load"

if [ "${RESET_LIMITS:-0}" = "1" ]; then
  echo "── RESET_LIMITS=1: сброс счётчиков рейт-лимитов (dev-эндпоинт)"
  curl -sf -o /dev/null -X POST -H 'X-Requested-With: XMLHttpRequest' \
    "$BASE/api/v1/dev/ratelimit-reset" || {
      echo "dev-эндпоинт недоступен: приложение на хосте запущено с профилем dev?" >&2
      exit 1
    }
fi

echo "── Проверка приложения (http://localhost:8080/api/v1/health)"
if ! timeout 120 bash -c 'until curl -sf http://localhost:8080/api/v1/health >/dev/null; do sleep 3; done'; then
  echo "Приложение не отвечает. Запустите: make run-local" >&2
  exit 1
fi

echo "── Проверка Keycloak (realm $REALM)"
if [ "$(curl -s -o /dev/null -w '%{http_code}' \
  "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")" != "200" ]; then
  echo "Keycloak недоступен на $KEYCLOAK — поднимите стек: make dev-run" >&2
  exit 1
fi

# Admin-токен realm'а master: bootstrap-администратор создаётся при пустой БД
# Keycloak (KC_BOOTSTRAP_ADMIN_*). Секрет не печатается.
ADMIN_TOKEN=$(curl -s -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' \
  --data-urlencode "username=${KEYCLOAK_ADMIN:-admin}" \
  --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD:-admin}" |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')
if [ -z "$ADMIN_TOKEN" ]; then
  echo "Admin-токен Keycloak не получен — проверьте KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD" >&2
  exit 1
fi

# Роль пула: имя realm-роли совпадает с UserRole приложения, поэтому
# KeycloakAuthenticationConverter перенесёт её в БД при первом входе.
ROLE_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KEYCLOAK/admin/realms/$REALM/roles/ROLE_USER" |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')
if [ -z "$ROLE_ID" ]; then
  echo "realm-роль ROLE_USER не найдена — realm импортирован?" >&2
  exit 1
fi

echo "── Сидирование пула: load-pool-$RUN_ID-p{1..$POOL_SIZE}@load.test"
for i in $(seq 1 "$POOL_SIZE"); do
  email="load-pool-${RUN_ID}-p${i}@load.test"
  payload=$(python3 - "$email" "$PASSWORD" "$i" <<'PY'
import json, sys
email, password, index = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "username": email,
    "email": email,
    "firstName": "Load",
    "lastName": f"Pool{index}",
    "enabled": True,
    "emailVerified": True,
    "credentials": [{"type": "password", "value": password, "temporary": False}],
}))
PY
)
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "$payload" "$KEYCLOAK/admin/realms/$REALM/users")
  echo "  create p$i -> $code"
  # 409 — пользователь уже есть (повторный прогон с тем же RUN_ID)
  if [ "$code" != "201" ] && [ "$code" != "409" ]; then
    echo "Неожиданный код $code (ожидался 201, повторное создание — 409)." >&2
    exit 1
  fi

  user_id=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    --get --data-urlencode "email=$email" --data-urlencode 'exact=true' \
    "$KEYCLOAK/admin/realms/$REALM/users" |
    python3 -c 'import json,sys; users=json.load(sys.stdin); print(users[0]["id"] if users else "")')
  if [ -z "$user_id" ]; then
    echo "Пользователь $email не найден после создания" >&2
    exit 1
  fi
  curl -s -o /dev/null -X POST \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "[{\"id\":\"$ROLE_ID\",\"name\":\"ROLE_USER\"}]" \
    "$KEYCLOAK/admin/realms/$REALM/users/$user_id/role-mappings/realm"
done

echo "── k6: user journey (VU=$TARGET_VUS, duration=$DURATION, pool=$POOL_SIZE, run=$RUN_ID)"
$COMPOSE run --rm --no-deps \
  -e RUN_ID="$RUN_ID" \
  -e POOL_SIZE="$POOL_SIZE" \
  -e TARGET_VUS="$TARGET_VUS" \
  -e DURATION="$DURATION" \
  -e KEYCLOAK_URL="http://host.docker.internal:${KEYCLOAK_PORT:-8180}" \
  -e REALM="$REALM" \
  k6

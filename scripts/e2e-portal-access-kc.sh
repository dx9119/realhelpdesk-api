#!/usr/bin/env bash
# E2E: права управления порталом через Keycloak (Bearer).
#
# Проверяет контур внешнего выпуска токенов на живом стеке:
#   - Bearer-токен Keycloak открывает API, а без токена и с мусорным — 401;
#   - пользователь из токена заводится в БД с источником KEYCLOAK;
#   - создание портала, чтение публичных/приватных настроек, смена видимости
#     (isPublic);
#   - доверенный пользователь читает участников и переименовывает портал;
#   - посторонний не читает участников (403);
#   - роль ROLE_USER vs ROLE_OPERATOR на /operator/tokens/cleanup (403/200);
#   - создание заявки и смена статуса;
#   - базовые уведомления (GET /notifications).
#
# Требования: поднятый стек (make up — сервисы профиля dev, включая keycloak),
# SPRING_PROFILES_ACTIVE=dev,keycloak (дефолт шаблона), docker для psql.
# Перед прогоном удаляет порталы владельца и связанные данные — прогон
# идемпотентен (имя портала фиксировано, лимит порталов на пользователя — 2).
# Использует существующих пользователей realm:
#   user@example.com / user-pass
#   operator@example.com / operator-pass

set -u

BASE=${BASE:-https://localhost:8443/api/v1}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
REALM=${REALM:-realhelpdesk}
CLIENT=${CLIENT:-realhelpdesk-api}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── Общие утилиты ─────────────────────────────────────────────
PSQL="docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tA -c"

COUNT_FAIL=0
COUNT_PASS=0

step() { echo; echo "── $1"; }
check() {
  if [ "$1" = "$2" ]; then
    COUNT_PASS=$((COUNT_PASS + 1))
    echo "  OK   $3 -> $2"
  else
    COUNT_FAIL=$((COUNT_FAIL + 1))
    echo "  FAIL $3 (факт: $1, ожидалось: $2)"
  fi
}
check_contains() {
  if printf '%s' "$2" | grep -q "$1"; then
    COUNT_PASS=$((COUNT_PASS + 1))
    echo "  OK   $3 содержит '$1'"
  else
    COUNT_FAIL=$((COUNT_FAIL + 1))
    echo "  FAIL $3 (нет '$1' в: $(printf '%s' "$2" | head -c 300))"
  fi
}
body_file() { printf '%s' "$1" > "$TMP/request-body.json"; printf '@%s' "$TMP/request-body.json"; }

# ── Keycloak helpers ──────────────────────────────────────────

kc_token() { # kc_token <email> <password> — печатает access_token
  local c
  c=$(curl -s -o "$TMP/kc-token.json" -w '%{http_code}' -X POST \
    "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=$CLIENT" \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "scope=openid")
  [ "$c" = "200" ] || { echo ""; return 1; }
  python3 -c "import json,sys; print(json.load(open('$TMP/kc-token.json')).get('access_token',''))"
}

kc_get() { # kc_get <token> <endpoint> — GET с Bearer, возвращает тело
  curl -sk -H "Authorization: Bearer $1" "$BASE$2"
}

kc_code() { # kc_code <token> <endpoint> [method] [body] — код ответа
  local method="${3:-GET}" body="${4:-}"
  local args=("-sk" -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1")
  [ "$method" != "GET" ] && [ "$method" != "DELETE" ] && args+=("-X" "$method")
  [ -n "$body" ] && args+=("-H" "Content-Type: application/json" -d "$body")
  curl "${args[@]}" "$BASE$2"
}

kc_post() { # kc_post <token> <endpoint> <body> — возвращает "статус|тело" (разделитель: первая пустая строка)
  local resp
  resp=$(curl -sk -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" -X POST "$BASE$2")
  local status
  status=$(echo "$resp" | tail -1)
  local body
  body=$(echo "$resp" | sed '$d')
  echo "${status}|${body}"
}

# ── Проверки ──────────────────────────────────────────────────

step "Готовность Keycloak и realm $REALM"
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")" != "200" ]; then
  echo "  FAIL Keycloak недоступен на $KEYCLOAK — поднимите стек: make up (профиль dev)"
  exit 1
fi
echo "  OK   discovery realm'а доступен"

# ── Токены пользователей ──────────────────────────────────────

USER_EMAIL="user@example.com"
USER_PASS="user-pass"
OPER_EMAIL="operator@example.com"
OPER_PASS="operator-pass"

USER_TOKEN=$(kc_token "$USER_EMAIL" "$USER_PASS")
if [ -z "$USER_TOKEN" ]; then
  echo "  FAIL токен $USER_EMAIL не выдан"
  exit 1
fi
echo "  OK   токен $USER_EMAIL получен"

OPER_TOKEN=$(kc_token "$OPER_EMAIL" "$OPER_PASS")
if [ -z "$OPER_TOKEN" ]; then
  echo "  FAIL токен $OPER_EMAIL не выдан"
  exit 1
fi
echo "  OK   токен $OPER_EMAIL получен"

# ── Базовая аутентификация ────────────────────────────────────

step "Базовая аутентификация"
check "$(kc_code "$USER_TOKEN" "/users/profile")" "200" "GET /users/profile с Bearer"
check "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/users/profile")" "401" "GET /users/profile без токена"
check "$(curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer not.a.token" "$BASE/users/profile")" "401" "GET /users/profile с мусорным токеном"

step "Учётная запись заведена в БД из токена"
check "$($PSQL "select user_platform_source from users where email='$USER_EMAIL';" | tail -1)" "KEYCLOAK" "источник учётной записи KEYCLOAK"
check "$($PSQL "select user_role from users where email='$USER_EMAIL';" | tail -1)" "ROLE_USER" "роль ROLE_USER"

# ── Создание портала ──────────────────────────────────────────

# Realm-пользователи фиксированы, имя портала — тоже, а лимит порталов
# на владельца — 2: без зачистки повторный прогон получает 409 на
# POST /portals. FK без ON DELETE CASCADE — удаляем в порядке зависимостей.
step "Очистка артефактов прошлых прогонов"
if $PSQL "delete from notifications where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from message_model where ticket_id in (select t.id from ticket_model t join portals p on p.id=t.portal_id join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from ticket_model where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from portal_transfer_requests where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from portal_access where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from portal_history where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email='$USER_EMAIL');
delete from portals where owner_id in (select id from users where email='$USER_EMAIL');" >/dev/null 2>&1; then
  echo "  OK   порталы $USER_EMAIL и связанные данные удалены"
else
  echo "  FAIL очистка БД не удалась — postgres поднят? (make up)"
  exit 1
fi

step "Создание портала владельцем (USER)"
RESP=$(kc_post "$USER_TOKEN" "/portals" '{"name":"kc-public-portal","description":"ключоак-контур"}')
PORTAL_STATUS="${RESP%%|*}"
BODY_CREATED="${RESP#*|}"
check "$PORTAL_STATUS" "201" "POST /portals"
PORTAL_ID=$(echo "$BODY_CREATED" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))")
echo "  portalId=$PORTAL_ID"

# ── Публичные эндпоинты ───────────────────────────────────────

step "Публичные эндпоинты"
check "$(curl -sk -o /dev/null -w '%{http_code}' -X POST "$BASE/captcha/challenge" \
  -H 'Content-Type: application/json' -d '{"action":"register"}')" "200" "POST /captcha/challenge без токена"
check "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/health")" "200" "GET /health без токена"

# ── Права на публичный портал ─────────────────────────────────

step "Права на приватный портал"
check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID")" "200" "GET /portals/{id} владельцем"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID")" "403" "GET /portals/{id} посторонним (портал приватен)"

check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID/settings")" "200" "GET /portals/{id}/settings владельцем"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/settings")" "403" "GET /portals/{id}/settings посторонним"

# ── Переименование ────────────────────────────────────────────

step "Переименование портала"
check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID" "PUT" "$(body_file '{"name":"kc-public-portal-v2","description":"обновлено владельцем"}')")" "200" "PUT /portals/{id} владельцем"

# ── Публичность ───────────────────────────────────────────────

step "Смена isPublic"
check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID/settings/visibility" "PUT" '{"isPublic":true}')" "204" "PUT /portals/{id}/settings/visibility владельцем"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/settings/visibility" "PUT" '{"isPublic":false}')" "403" "PUT /portals/{id}/settings/visibility посторонним"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID")" "200" "GET /portals/{id} посторонним (портал публичен)"

# ── Доступ по пользователям ────────────────────────────────────

step "Доставление пользователя к порталу"
OPERATOR_ID=$($PSQL "select id from users where email='$OPER_EMAIL';" | tail -1)
check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID/settings/users" "PUT" "{\"userIds\":[$OPERATOR_ID]}")" "200" "PUT /portals/{id}/settings/users владельцем"

check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/settings")" "200" "GET /portals/{id}/settings доверенным"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID" "PUT" "$(body_file '{"name":"public-portal-kc-v3","description":"обновлено доверенным"}')")" "200" "PUT /portals/{id} доверенным"

# ── Создание заявки и смена статуса ───────────────────────────

step "Создание заявки"
TICKET_RESP=$(kc_post "$USER_TOKEN" "/portals/$PORTAL_ID/tickets" '{"title":"KC заявка","body":"тело заявки"}')
TICKET_STATUS="${TICKET_RESP%%|*}"
TICKET_BODY="${TICKET_RESP#*|}"
check "$TICKET_STATUS" "201" "POST /portals/{id}/tickets"
TICKET_ID=$(echo "$TICKET_BODY" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))")
if [ -n "$TICKET_ID" ]; then
  COUNT_PASS=$((COUNT_PASS + 1))
  echo "  OK   заявка создана, id=$TICKET_ID"
else
  COUNT_FAIL=$((COUNT_FAIL + 1))
  echo "  FAIL не удалось извлечь ticket id"
fi

check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/tickets/$TICKET_ID/status" "PUT" '{"status":"IN_PROGRESS"}')" "204" "PUT /portals/{id}/tickets/{id}/status доверенным"

# ── История портала ────────────────────────────────────────────

step "История портала"
HIST=$(kc_get "$OPER_TOKEN" "/portals/$PORTAL_ID/history?size=10")
check_contains "VISIBILITY_CHANGED" "$HIST" "история: VISIBILITY_CHANGED"
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/history")" "200" "GET /portals/{id}/history доверенным"

# ── Операторский эндпоинт ──────────────────────────────────────

step "Права на операторский эндпоинт"
check "$(kc_code "$OPER_TOKEN" "/operator/tokens/cleanup" "POST")" "200" "ROLE_OPERATOR — POST /operator/tokens/cleanup"
check "$(kc_code "$USER_TOKEN" "/operator/tokens/cleanup" "POST")" "403" "ROLE_USER — POST /operator/tokens/cleanup"
check "$(curl -sk -o /dev/null -w '%{http_code}' -X POST "$BASE/operator/tokens/cleanup")" "401" "без токена — 401"

# ── Уведомления (базовые) ─────────────────────────────────────

step "Уведомления"
check "$(kc_code "$USER_TOKEN" "/notifications")" "200" "GET /notifications"
check "$(kc_code "$USER_TOKEN" "/notifications/unread-count")" "200" "GET /notifications/unread-count"
check "$(kc_code "$USER_TOKEN" "/notifications/preferences")" "200" "GET /notifications/preferences"

echo
if [ "$COUNT_FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$COUNT_FAIL"

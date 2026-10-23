#!/usr/bin/env bash
# E2E: уведомления через Keycloak (Bearer).
#
# Проверяет:
#   - SSE-канал открывается комментарием connected и отдаёт backfill;
#   - long polling будится при создании заявки;
#   - автор действия не получает уведомление о своей заявке;
#   - смена статуса уведомляет владельца;
#   - отключённое событие не приходит (пустой long polling);
#   - чужое уведомление не читается (404);
#   - без токена — 401.
#
# Требования: поднятый стек (make up — сервисы профиля dev, включая keycloak),
# SPRING_PROFILES_ACTIVE=dev,keycloak.
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
    echo "  FAIL $3 (факт: $2, ожидалось: $1)"
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

kc_token() {
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

kc_get() { # GET с Bearer, возвращает тело
  curl -sk -H "Authorization: Bearer $1" "$BASE$2"
}

kc_code() { # код ответа
  local method="${3:-GET}" body="${4:-}"
  local args=("-sk" -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1")
  [ "$method" != "GET" ] && [ "$method" != "DELETE" ] && args+=("-X" "$method")
  [ -n "$body" ] && args+=("-H" "Content-Type: application/json" -d "$body")
  curl "${args[@]}" "$BASE$2"
}

kc_post() { # "статус|тело"
  local resp
  resp=$(curl -sk -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" -X POST "$BASE$2")
  local status
  status=$(echo "$resp" | tail -1)
  local body
  body=$(echo "$resp" | sed '$d')
  echo "${status}|${body}"
}

# unread <token> — счётчик непрочитанных
unread() {
  curl -sk -H "Authorization: Bearer $1" "$BASE/notifications/unread-count" | python3 -c "import sys,json;print(json.load(sys.stdin).get('count',-1))"
}

# last_id <token> — максимальный id уведомлений
last_id() {
  curl -sk -H "Authorization: Bearer $1" "$BASE/notifications?size=50" | python3 -c "
import sys,json
d=json.load(sys.stdin)
ids=[i.get('id',0) for i in d.get('content',[])]
print(max(ids) if ids else 0)
"
}

# await_grep <file> <pattern> [tries] — ждёт паттерн в файле
await_grep() {
  local file="$1" pattern="$2" tries="${3:-5}" i=0
  for _ in $(seq 1 "$tries"); do
    grep -q "$pattern" "$file" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# ── Проверки ──────────────────────────────────────────────────

step "Готовность Keycloak"
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")" != "200" ]; then
  echo "  FAIL Keycloak недоступен — поднимите стек: make up"
  exit 1
fi
echo "  OK   discovery realm'а доступен"

USER_EMAIL="user@example.com"
USER_PASS="user-pass"
OPER_EMAIL="operator@example.com"
OPER_PASS="operator-pass"

USER_TOKEN=$(kc_token "$USER_EMAIL" "$USER_PASS")
[ -z "$USER_TOKEN" ] && { echo "  FAIL токен $USER_EMAIL"; exit 1; }
echo "  OK   токен $USER_EMAIL получен"

OPER_TOKEN=$(kc_token "$OPER_EMAIL" "$OPER_PASS")
[ -z "$OPER_TOKEN" ] && { echo "  FAIL токен $OPER_EMAIL"; exit 1; }
echo "  OK   токен $OPER_EMAIL получен"

# ── Создание портала оператором ──────────────────────────────

step "Создание портала оператором"
RESP=$(kc_post "$OPER_TOKEN" "/portals" '{"name":"notify-portal-kc","description":"уведомления ключоак"}')
PORTAL_STATUS="${RESP%%|*}"
PORTAL_BODY="${RESP#*|}"
check "$PORTAL_STATUS" "201" "POST /portals оператором"
PORTAL_ID=$(echo "$PORTAL_BODY" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))")
echo "  portalId=$PORTAL_ID"

# ── Доступ пользователя к порталу ─────────────────────────────

step "Доступ пользователя к порталу"
OPERATOR_ID=$($PSQL "select id from users where email='$OPER_EMAIL';" | tail -1)
USER_ID=$($PSQL "select id from users where email='$USER_EMAIL';" | tail -1)
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/settings/users" "PUT" "{\"userIds\":[$USER_ID]}")" "200" "выдал доступ пользователю"

# ── Уведомления: базовые ─────────────────────────────────────

step "Уведомления: базовые"
check 200 "$(kc_code "$USER_TOKEN" "/notifications")" "GET /notifications"
check "$(unread "$USER_TOKEN")" "0" "у пользователя непрочитанных нет"
check 200 "$(kc_code "$USER_TOKEN" "/notifications/preferences")" "GET /preferences"

# ── Long polling ──────────────────────────────────────────────

step "Long polling: оператор создаёт заявку, пользователь получает NEW_TICKET"
curl -sk -H "Authorization: Bearer $USER_TOKEN" -o "$TMP/wait1.json" -w '%{http_code}' \
  "$BASE/notifications/wait?timeoutSec=15" > "$TMP/wait1.code" &
WAIT_PID=$!
sleep 1
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/tickets" "POST" "$(body_file '{"title":"Первая KC заявка","body":"тело"}')")" "201" "оператор создал заявку"
wait "$WAIT_PID"
check "$(cat "$TMP/wait1.code")" "200" "long polling пользователя завершился 200"
if grep -q '"event":"NEW_TICKET"' "$TMP/wait1.json"; then
  COUNT_PASS=$((COUNT_PASS + 1))
  echo "  OK   пользователь получил NEW_TICKET по long polling"
else
  COUNT_FAIL=$((COUNT_FAIL + 1))
  echo "  FAIL пользователь не получил NEW_TICKET: $(cat "$TMP/wait1.json")"
fi

# ── Автор не получает уведомление ─────────────────────────────

step "Автор не получает уведомление"
check "$(unread "$OPER_TOKEN")" "0" "у оператора непрочитанных нет (сам создал заявку)"
check "$(unread "$USER_TOKEN")" "1" "у пользователя одно непрочитанное"

# ── SSE ──────────────────────────────────────────────────────

step "SSE: connected и backfill"
curl -sk -N -m 10 -H "Authorization: Bearer $USER_TOKEN" \
  "$BASE/notifications/stream?afterId=0" > "$TMP/sse-backfill.txt" 2>/dev/null &
SSE_PID=$!
check "$(await_grep "$TMP/sse-backfill.txt" 'connected' 5)" "1" "SSE открылся комментарием connected"
check "$(await_grep "$TMP/sse-backfill.txt" '"event":"NEW_TICKET"' 5)" "1" "backfill отдал NEW_TICKET"
kill "$SSE_PID" 2>/dev/null || true
wait "$SSE_PID" 2>/dev/null || true

# ── Прочтение ─────────────────────────────────────────────────

step "Прочтение"
check "$(kc_code "$USER_TOKEN" "/notifications/read-all" "PUT")" "204" "прочитал все"
check "$(unread "$USER_TOKEN")" "0" "счётчик стал 0"

# ── Смена статуса уведомляет владельца ────────────────────────

step "Смена статуса"
TICKET_ID=$($PSQL "select id from ticket_model order by id desc limit 1;" | tail -1)
check "$(kc_code "$USER_TOKEN" "/portals/$PORTAL_ID/tickets/$TICKET_ID/status" "PUT" '{"status":"IN_PROGRESS"}')" "204" "сменил статус"
check "$(unread "$OPER_TOKEN")" "1" "у оператора уведомление о смене статуса"

# ── Preferences: отключение событий ───────────────────────────

step "Отключение событий"
check "$(kc_code "$USER_TOKEN" "/notifications/preferences" "PUT" '{"events":["CHANGE_TICKET"]}')" "200" "сохранил preferences"
if curl -sk -H "Authorization: Bearer $USER_TOKEN" "$BASE/notifications/preferences" | grep -q '"CHANGE_TICKET"'; then
  COUNT_PASS=$((COUNT_PASS + 1))
  echo "  OK   в настройках остался CHANGE_TICKET"
else
  COUNT_FAIL=$((COUNT_FAIL + 1))
  echo "  FAIL настройки не сохранились"
fi

step "Отключённое событие не приходит"
AFTER=$(last_id "$USER_TOKEN")
curl -sk -H "Authorization: Bearer $USER_TOKEN" -o "$TMP/wait2.json" -w '%{http_code}' \
  "$BASE/notifications/wait?timeoutSec=2&afterId=${AFTER}" > "$TMP/wait2.code" &
WAIT_PID=$!
sleep 1
check "$(kc_code "$OPER_TOKEN" "/portals/$PORTAL_ID/tickets" "POST" "$(body_file '{"title":"Вторая KC заявка","body":"проверка отключения"}')")" "201" "оператор создал вторую заявку"
wait "$WAIT_PID"
check "$(cat "$TMP/wait2.code")" "200" "long polling завершился по таймауту"
if grep -q '"content":\[\]' "$TMP/wait2.json"; then
  COUNT_PASS=$((COUNT_PASS + 1))
  echo "  OK   NEW_TICKET не пришёл (событие отключено)"
else
  COUNT_FAIL=$((COUNT_FAIL + 1))
  echo "  FAIL ожидали пустой content"
fi

# ── Права ─────────────────────────────────────────────────────

step "Права"
check 401 "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/notifications")" "без токена — 401"
check 404 "$(curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $USER_TOKEN" -X PUT "$BASE/notifications/999999/read")" "чужое/несуществующее — 404"

echo
if [ "$COUNT_FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$COUNT_FAIL"

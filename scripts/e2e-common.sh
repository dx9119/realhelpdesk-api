#!/usr/bin/env bash
# Общие утилиты для e2e-скриптов Keycloak-контура.
#
# Подключайте через: source "$(dirname "$0")/e2e-common.sh"
#
# Функции разделены на группы:
#   - kc_*      — работа с Keycloak (direct access grant, Bearer-запросы)
#   - общие     — без префикса (проверки, почта, JSON)

set -u

# ── Общие переменные ──────────────────────────────────────────
BASE=${BASE:-http://localhost:8080/api/v1}
SMTP=${SMTP:-http://localhost:3000}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
REALM=${REALM:-realhelpdesk}
CLIENT=${CLIENT:-realhelpdesk-api}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# docker compose exec — имя контейнера генерируется compose само
PSQL="docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tA -c"

# ── Общие функции ─────────────────────────────────────────────

# count_fail — счётчик провалов (общий для всех контуров)
COUNT_FAIL=0
COUNT_PASS=0

step() { echo; echo "── $1"; }

check() { # check <ожидаемый код> <фактический код> <описание>
  if [ "$1" = "$2" ]; then
    COUNT_PASS=$((COUNT_PASS + 1))
    echo "  OK   $3 -> $2"
  else
    COUNT_FAIL=$((COUNT_FAIL + 1))
    echo "  FAIL $3 (факт: $2, ожидалось: $1)"
  fi
}

check_contains() { # check_contains <паттерн> <строка> <описание>
  if printf '%s' "$2" | grep -q "$1"; then
    COUNT_PASS=$((COUNT_PASS + 1))
    echo "  OK   $3 содержит '$1'"
  else
    COUNT_FAIL=$((COUNT_FAIL + 1))
    echo "  FAIL $3 (нет '$1' в: $(printf '%s' "$2" | head -c 300))"
  fi
}

# http_code <url> [доп. аргументы curl] — код ответа
http_code() {
  curl -sk -o /dev/null -w '%{http_code}' "$@"
}

# http_body <url> [доп. аргументы curl] — тело ответа
http_body() {
  curl -sk -o - "$@"
}

# wait_mail <email> <паттерн_в_теме> [tries] — ждёт письмо в smtp4dev
wait_mail() {
  local email="$1" pattern="${2:-}" tries="${3:-10}" i=0 msg=""
  for i in $(seq 1 "$tries"); do
    msg=$(curl -s "$SMTP/api/Messages?limit=50" | python3 -c "
import sys, json
r = json.load(sys.stdin).get('results', [])
for m in r:
    if m.get('deliveredTo') == sys.argv[1] and (not sys.argv[2] or sys.argv[2] in m.get('subject','')):
        print(m['id']); break
" "$email" "$pattern")
    [ -n "$msg" ] && return 0
    sleep 1
  done
  return 1
}

# get_mail_body <message_id> — тело письма
get_mail_body() {
  curl -s "$SMTP/api/Messages/$1/PlainText"
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

# json_field <python_expr> <file> — извлечение поля из JSON через python3
json_field() {
  python3 -c "import sys,json; print(json.load(open(sys.argv[2]))$1)" "$1" "$2" 2>/dev/null || echo ""
}

# json_field_stdin <python_expr> — извлечение поля из stdin
json_field_stdin() {
  python3 -c "import sys,json; print(json.load(sys.stdin)$1)" 2>/dev/null || echo ""
}

# ── Функции для Keycloak-контура ─────────────────────────────

# kc_token <email> <password> — получает access_token через direct access grant
kc_token() {
  local email="${1:?email required}" pass="${2:?password required}"
  local token_file="$TMP/kc-token.json"
  local c
  c=$(curl -s -o "$token_file" -w '%{http_code}' -X POST \
    "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=$CLIENT" \
    --data-urlencode "username=$email" \
    --data-urlencode "password=$pass" \
    --data-urlencode "scope=openid")
  if [ "$c" = "200" ]; then
    python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('access_token',''))" "$token_file"
    return 0
  fi
  echo ""
  return 1
}

# kc_auth_header <token> — возвращает строку Authorization: Bearer <token>
kc_auth_header() {
  echo "Authorization: Bearer $1"
}

# kc_user <email> <password> — логин + возвращает токен (shortcut)
kc_user() {
  local email="${1:?email required}" pass="${2:?password required}"
  kc_token "$email" "$pass"
}

# check_kc_ready — проверяет готовность Keycloak
check_kc_ready() {
  local c
  c=$(curl -s -o /dev/null -w '%{http_code}' "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")
  if [ "$c" = "200" ]; then
    echo "  OK   Keycloak доступен, realm '$REALM' найден"
    return 0
  fi
  echo "  FAIL Keycloak недоступен на $KEYCLOAK — поднимите стек: make dev-run"
  return 1
}

# kc_auth_code <endpoint> [method] [body] — запрос с Bearer-токеном, возвращает код
kc_auth_code() {
  local endpoint="$1" method="${2:-GET}" body="${3:-}" token="$4"
  local headers=()
  [ -n "$token" ] && headers+=("-H" "Authorization: Bearer $token")
  [ "$method" != "GET" ] && [ "$method" != "DELETE" ] && headers+=("-X" "$method")
  if [ -n "$body" ]; then
    headers+=("-H" "Content-Type: application/json" -d "$body")
  fi
  curl -sk -o /dev/null -w '%{http_code}' "${headers[@]}" "$BASE$endpoint"
}

# kc_auth_body <endpoint> [method] [body] — запрос с Bearer-токеном, возвращает тело
kc_auth_body() {
  local endpoint="$1" method="${2:-GET}" body="${3:-}" token="$4"
  local headers=()
  [ -n "$token" ] && headers+=("-H" "Authorization: Bearer $token")
  [ "$method" != "GET" ] && [ "$method" != "DELETE" ] && headers+=("-X" "$method")
  if [ -n "$body" ]; then
    headers+=("-H" "Content-Type: application/json" -d "$body")
  fi
  curl -sk "${headers[@]}" "$BASE$endpoint"
}

# body_file <json> — записывает JSON в файл, возвращает @path (для curl --data-binary)
body_file() {
  local bf="$TMP/request-body.json"
  printf '%s' "$1" > "$bf"
  printf '@%s' "$bf"
}

# ── Инициализация ────────────────────────────────────────────

# check_base — базовые проверки доступности стека
check_base() {
  if [ "$(http_code "$BASE/health")" != "200" ]; then
    echo "  FAIL $BASE/health недоступен — запустите приложение: make run-local"
    exit 1
  fi
  if [ "$($PSQL 'select 1;' | tail -1)" != "1" ]; then
    echo "  FAIL postgres недоступен — поднимите инфраструктуру: make dev-run"
    exit 1
  fi
}

#!/usr/bin/env bash
# E2E: вход через Keycloak (OAuth2 Resource Server) — профиль приложения keycloak.
#
# Проверяет контур внешнего выпуска токенов на живом стеке:
#   - realm realhelpdesk импортирован, direct access grant выдаёт токен;
#   - Bearer-токен Keycloak открывает API, а без токена и с мусорным — 401;
#   - локальный вход приложения (/api/v1/auth/login) в этом профиле недоступен;
#   - пользователь из токена заводится в БД с источником KEYCLOAK;
#   - realm-роль ROLE_OPERATOR из токена переносится в БД (синхронизация роли);
#   - доступ оператора к /operator/tokens/cleanup различается по роли (403/200);
#   - публичные эндпоинты (капча, health) остаются без токена;
#   - блокировка учётной записи в БД закрывает доступ по валидному токену.
#
# Требования: поднятый стек (make up — сервисы профиля dev, включая keycloak),
# SPRING_PROFILES_ACTIVE=dev,keycloak (дефолт шаблона), docker для psql.
# Идемпотентен: пользователи из realm создаются при первом входе, повторный
# прогон их переиспользует.
set -u

BASE=${BASE:-https://localhost:8443}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
REALM=${REALM:-realhelpdesk}
CLIENT=${CLIENT:-realhelpdesk-api}
USER_EMAIL=${USER_EMAIL:-user@example.com}
USER_PASS=${USER_PASS:-user-pass}
OPER_EMAIL=${OPER_EMAIL:-operator@example.com}
OPER_PASS=${OPER_PASS:-operator-pass}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
TOKEN_FILE="$TMP/token.json"
BODY="$TMP/body.txt"
PSQL="docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tA -c"

FAIL=0
step() { echo; echo "── $1"; }
check() {
  if [ "$1" = "$2" ]; then
    echo "  OK   $3"
  else
    echo "  FAIL $3 (факт: $1, ожидалось: $2)"
    FAIL=1
  fi
}
code() { curl -sk -o "$BODY" -w "%{http_code}" "$@"; }
sql() { $PSQL "$1" | tail -1; }

# Токен Keycloak: direct access grant (password) — тот же client realhelpdesk-api,
# что у фронтенда, поэтому проверяется и клиент realm'а, и права пользователя.
keycloak_token() { # keycloak_token <email> <пароль> — печатает access_token
  local c
  c=$(curl -s -o "$TOKEN_FILE" -w "%{http_code}" -X POST "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=$CLIENT" \
    --data-urlencode "username=$1" --data-urlencode "password=$2" --data-urlencode "scope=openid")
  [ "$c" = "200" ] || { echo "" ; return 1; }
  python3 -c "import json,sys; print(json.load(open('$TOKEN_FILE')).get('access_token',''))"
}

echo "═══ Вход через Keycloak (профиль keycloak) ═══"

step "Готовность Keycloak и realm $REALM"
if [ "$(code "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")" != "200" ]; then
  echo "  FAIL Keycloak недоступен на $KEYCLOAK — поднимите стек: make up (профиль dev)"
  exit 1
fi
echo "  OK   discovery realm'а доступен"

step "Локальный вход приложения выключен профилем keycloak"
# В этом профиле AuthController не создаётся, а /api/v1/auth/login не входит в
# белый список: запрос уходит в 401, а не в 200 локального контура.
check "$(code -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASS\"}")" "401" "POST /api/v1/auth/login"

step "Токен пользователя $USER_EMAIL"
USER_TOKEN=$(keycloak_token "$USER_EMAIL" "$USER_PASS")
if [ -n "$USER_TOKEN" ]; then
  echo "  OK   access-токен получен (direct access grant)"
else
  echo "  FAIL токен не выдан — проверьте realm и учётные данные"
  exit 1
fi
check "$(python3 -c "import json,base64,sys; p=open('$TOKEN_FILE').read(); c=json.loads(p); t=c['access_token'].split('.')[1]; t+='='*(-len(t)%4); d=json.loads(base64.urlsafe_b64decode(t)); print(d.get('iss',''))")" \
  "$KEYCLOAK/realms/$REALM" "issuer токена совпадает с realm'ом"
check "$(python3 -c "import json,base64; c=json.load(open('$TOKEN_FILE')); t=c['access_token'].split('.')[1]; t+='='*(-len(t)%4); d=json.loads(base64.urlsafe_b64decode(t)); print('ROLE_USER' in d.get('realm_access',{}).get('roles',[]))")" \
  "True" "realm_access.roles содержит ROLE_USER"

step "Запросы к API с токеном Keycloak"
check "$(code -H "Authorization: Bearer $USER_TOKEN" "$BASE/api/v1/users/profile")" "200" "GET /users/profile с Bearer"
check "$(code "$BASE/api/v1/users/profile")" "401" "GET /users/profile без токена"
check "$(code -H "Authorization: Bearer not.a.token" "$BASE/api/v1/users/profile")" "401" "GET /users/profile с мусорным токеном"

step "Учётная запись заведена в БД из токена"
check "$(sql "select user_platform_source from users where email='$USER_EMAIL';")" "KEYCLOAK" "источник учётной записи KEYCLOAK"
check "$(sql "select user_role from users where email='$USER_EMAIL';")" "ROLE_USER" "роль из realm-ролей"
check "$(sql "select is_email_verified from users where email='$USER_EMAIL';")" "t" "email подтверждён Keycloak'ом"

step "Синхронизация роли оператора"
OPER_TOKEN=$(keycloak_token "$OPER_EMAIL" "$OPER_PASS")
if [ -z "$OPER_TOKEN" ]; then
  echo "  FAIL токен оператора не выдан"
  FAIL=1
else
  echo "  OK   access-токен оператора получен"
fi
# Учётная запись и роль появляются в БД при первом запросе с токеном
# (KeycloakAuthenticationConverter) — до него проверять нечего.
check "$(code -H "Authorization: Bearer $OPER_TOKEN" "$BASE/api/v1/users/profile")" "200" "первый запрос оператора"
check "$(sql "select user_role from users where email='$OPER_EMAIL';")" "ROLE_OPERATOR" "realm-роль ROLE_OPERATOR перенесена в БД"

step "Права на операторский эндпоинт"
check "$(code -H "Authorization: Bearer $OPER_TOKEN" -X POST "$BASE/api/v1/operator/tokens/cleanup")" "200" "ROLE_OPERATOR — очистка токенов"
check "$(code -H "Authorization: Bearer $USER_TOKEN" -X POST "$BASE/api/v1/operator/tokens/cleanup")" "403" "ROLE_USER — доступ запрещён"
check "$(code -X POST "$BASE/api/v1/operator/tokens/cleanup")" "401" "без токена — требуется аутентификация"

step "Публичные эндпоинты остаются открытыми"
check "$(code -X POST "$BASE/api/v1/captcha/challenge" -H 'Content-Type: application/json' \
  -d "{\"action\":\"register\"}")" "200" "POST /api/v1/captcha/challenge"
check "$(code "$BASE/api/v1/health")" "200" "GET /api/v1/health"

step "Блокировка учётной записи закрывает доступ по валидному токену"
sql "update users set user_status='BLOCKED' where email='$USER_EMAIL';" >/dev/null
check "$(code -H "Authorization: Bearer $USER_TOKEN" "$BASE/api/v1/users/profile")" "401" "BLOCKED — 401"
sql "update users set user_status='ACTIVE' where email='$USER_EMAIL';" >/dev/null
check "$(code -H "Authorization: Bearer $USER_TOKEN" "$BASE/api/v1/users/profile")" "200" "ACTIVE — доступ возвращён"

echo
echo "Итого: FAIL $FAIL"
[ "$FAIL" -eq 0 ]

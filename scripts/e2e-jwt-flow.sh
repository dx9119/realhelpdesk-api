#!/usr/bin/env bash
# E2E: JWT-поток аутентификации.
#
# Проверяет исправления аудита JWT:
#   - разграничение типа токена (refresh-токен не работает как access)
#   - обновление access через /auth/tokens/access (refresh-cookie с path=/api/v1/auth)
#   - мгновенный отзыв access-токена при логауте (claim ver + token_version)
#   - отзыв всех refresh-токенов при смене пароля
#   - отказ неактивным/неаутентифицированным запросам
#
# Требует запущенный стек: make dev-up (API :8443, smtp4dev :3000).
# Капча должна быть выключена (по умолчанию).
# Счетчики рейт-лимитов живут в памяти: при исчерпании перезапустите
# приложение (make dev-restart) и повторите запуск.
set -u

BASE=${BASE:-https://localhost:8443}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
JAR="$TMP/cookies.txt"
HDR="$TMP/headers.txt"

EMAIL="e2e-jwt-flow-$RANDOM$RANDOM@example.com"
PASS1="Passw0rd!123"
PASS2="Passw0rd!456"
PASS3="Passw0rd!789"
PASS=0
FAIL=0

check() { # check <ожидаемый код> <фактический код> <описание>
  if [ "$1" = "$2" ]; then
    PASS=$((PASS+1)); echo "  OK   $3 -> $2"
  else
    FAIL=$((FAIL+1)); echo "  FAIL $3 (факт: $2, ожидалось: $1)"
  fi
}
step() { echo; echo "── $1"; }
cookie() { awk -v n="$1" '$6==n {print $7}' "$JAR" | tail -1; }
status() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -w '%{http_code}' "$@"; }
header_cookie_value() { # значение cookie из последнего ответа (Set-Cookie)
  tr -d '\r' < "$HDR" | grep -i "^set-cookie: $1=" | tail -1 | sed -E "s/^[Ss]et-[Cc]ookie: $1=([^;]+).*/\1/"
}

step "Регистрация ($EMAIL)"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o /dev/null -c "$JAR" -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"E2E\",\"lastName\":\"Flow\",\"email\":\"$EMAIL\",\"password\":\"$PASS1\"}"
check 201 "$(head -1 "$HDR" | awk '{print $2}')" "POST /auth/register"
if [ -n "$(cookie accessToken)" ] && [ -n "$(cookie refreshToken)" ]; then
  PASS=$((PASS+1)); echo "  OK   access и refresh cookie установлены"
else
  FAIL=$((FAIL+1)); echo "  FAIL cookie не получены"
fi

step "Авторизованный запрос"
check 200 "$(status -b "$JAR" "$BASE/api/v1/users/profile")" "GET /users/profile"

step "Тип токена: refresh-cookie вместо access"
check 401 "$(status -H "Cookie: accessToken=$(cookie refreshToken)" "$BASE/api/v1/users/profile")" "refresh как access"

step "Обновление access через /auth/tokens/access"
OLD_REFRESH=$(cookie refreshToken)
check 200 "$(status -b "$JAR" -c "$JAR" -X POST "$BASE/api/v1/auth/tokens/access")" "POST /auth/tokens/access"
NEW_AT=$(header_cookie_value accessToken)
if [ -n "$NEW_AT" ]; then
  PASS=$((PASS+1)); echo "  OK   новый access-токен выдан"
else
  FAIL=$((FAIL+1)); echo "  FAIL новый access не выдан"
fi
check 200 "$(status -H "Cookie: accessToken=$NEW_AT" "$BASE/api/v1/users/profile")" "GET /users/profile с новым access"

step "Логаут: access-токен отзывается немедленно"
check 204 "$(status -b "$JAR" -c "$JAR" -X DELETE "$BASE/api/v1/auth/session")" "DELETE /auth/session"
check 401 "$(status -H "Cookie: accessToken=$NEW_AT" "$BASE/api/v1/users/profile")" "старый access после логаута"
check 401 "$(status -X POST -H "Cookie: refreshToken=$OLD_REFRESH" "$BASE/api/v1/auth/tokens/access")" "refresh после логаута"

step "Повторный вход"
rm -f "$JAR"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o /dev/null -c "$JAR" -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS1\"}"
check 200 "$(head -1 "$HDR" | awk '{print $2}')" "POST /auth/login"
AT2=$(cookie accessToken)
RT2=$(cookie refreshToken)
check 200 "$(status -b "$JAR" "$BASE/api/v1/users/profile")" "GET /users/profile после входа"

step "Смена пароля: отзываются все токены"
REQ_STATUS=$(status -X POST "$BASE/api/v1/users/password-resets" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\"}")
if [ "$REQ_STATUS" = "429" ]; then
  echo "  FAIL /users/password-resets -> 429: лимит 3 / 10 мин исчерпан."
  echo "       Счетчики в памяти приложения: make dev-restart и повторите запуск."
  echo; echo "Итого: OK $PASS, FAIL $((FAIL+1))"
  exit 1
fi
check 204 "$REQ_STATUS" "POST /users/password-resets"
sleep 2
MSG=$(curl -s "http://localhost:3000/api/Messages" \
  | python3 -c "import sys,json; r=json.load(sys.stdin).get('results',[]); print(r[0]['id'] if r else '')")
RESET_CODE=$(curl -s "http://localhost:3000/api/Messages/$MSG/PlainText" \
  | grep -oE 'code=-?[0-9]+' | head -1 | cut -d= -f2)
if [ -n "$RESET_CODE" ]; then
  PASS=$((PASS+1)); echo "  OK   код сброса получен из smtp4dev"
else
  FAIL=$((FAIL+1)); echo "  FAIL код сброса не найден"
fi
check 204 "$(status -X PUT "$BASE/api/v1/users/password-resets/$RESET_CODE" \
  -H 'Content-Type: application/json' -d "{\"password\":\"$PASS2\"}")" "PUT /users/password-resets/{code}"
check 401 "$(status -H "Cookie: accessToken=$AT2" "$BASE/api/v1/users/profile")" "access до смены пароля (отзыв)"
check 401 "$(status -X POST -H "Cookie: refreshToken=$RT2" "$BASE/api/v1/auth/tokens/access")" "refresh до смены пароля (отзыв)"

step "Вход с новым паролем"
rm -f "$JAR"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o /dev/null -c "$JAR" -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS2\"}"
check 200 "$(head -1 "$HDR" | awk '{print $2}')" "POST /auth/login с новым паролем"
check 200 "$(status -b "$JAR" "$BASE/api/v1/users/profile")" "GET /users/profile после смены пароля"

step "Отказы"
check 401 "$(status -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS3\"}")" "неверный пароль"
check 401 "$(status "$BASE/api/v1/users/profile")" "запрос без cookie"

echo
echo "Итого: OK $PASS, FAIL $FAIL"
[ "$FAIL" -eq 0 ]

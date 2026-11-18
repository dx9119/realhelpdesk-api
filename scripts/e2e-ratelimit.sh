#!/usr/bin/env bash
# E2E: рейт-лимиты.
#
# Проверяет троттлинг по IP + эндпоинту (ответ 429 + Retry-After).
# Лимиты действуют одинаково при включенной и выключенной капче:
#   POST /captcha/challenge             30 / 60 сек
#   POST /captcha/solve                 30 / 60 сек
#   POST /email/codes                    3 / 10 мин (нужен Bearer-токен)
#
# Счетчики живут в памяти приложения: если лимит уже исчерпан прошлым
# запуском, скрипт об этом скажет — перезапустите приложение
# (Ctrl+C + make run-local) и повторите. Пока окно /captcha/challenge не истекло
# (60 сек), проверка капчи не сойдется. Капча должна быть выключена
# (по умолчанию).
set -u

BASE=${BASE:-http://localhost:8080/api/v1}
KEYCLOAK=${KEYCLOAK:-http://localhost:${KEYCLOAK_PORT:-8180}}
REALM=${REALM:-realhelpdesk}
CLIENT=${CLIENT:-realhelpdesk-api}
USER_EMAIL=${USER_EMAIL:-user@example.com}
USER_PASS=${USER_PASS:-user-pass}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body.txt"
HEADERS="$TMP/headers.txt"

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
check_ge() {
  if [ "$1" -ge "$2" ]; then
    echo "  OK   $3"
  else
    echo "  FAIL $3 (факт: $1, ожидалось >= $2)"
    FAIL=1
  fi
}

# Bearer-токен Keycloak: /email/codes за аутентификацией.
token=$(curl -s -X POST "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' --data-urlencode "client_id=$CLIENT" \
  --data-urlencode "username=$USER_EMAIL" --data-urlencode "password=$USER_PASS" \
  --data-urlencode 'scope=openid' |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')
if [ -z "$token" ]; then
  echo "FAIL токен Keycloak не получен — поднимите стек (make dev-run) и приложение (make run-local)"
  exit 1
fi

echo "═══ Рейт-лимиты ═══"

step "POST /captcha/challenge: 31 запрос подряд (лимит 30/60с)"
challenge_body='{"action":"email-code"}'
first=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -H 'Content-Type: application/json' -d "$challenge_body" -o /dev/null -w "%{http_code}" -X POST "$BASE/captcha/challenge")
if [ "$first" != "200" ]; then
  echo "  FAIL окно /captcha/challenge уже исчерпано (http=$first)"
  echo "  подсказка: перезапустите приложение (Ctrl+C + make run-local) && bash scripts/e2e-ratelimit.sh"
  exit 1
fi
ok=1; r429=0
for i in $(seq 1 30); do
  c=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -H 'Content-Type: application/json' -d "$challenge_body" -o /dev/null -w "%{http_code}" -X POST "$BASE/captcha/challenge")
  [ "$c" = "200" ] && ok=$((ok + 1))
  [ "$c" = "429" ] && r429=$((r429 + 1))
done
check "$((ok + r429))" "31" "все 31 запрос обработаны"
check_ge "$r429" "1" "после лимита пришел 429"

step "Ответ 429 несёт Retry-After и JSON"
status=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -H 'Content-Type: application/json' -d "$challenge_body" -D "$HEADERS" -o "$BODY" -w "%{http_code}" -X POST "$BASE/captcha/challenge")
check "$status" "429" "статус"
check "$(grep -ci '^retry-after:' "$HEADERS")" "1" "заголовок Retry-After присутствует"
grep -qi 'Слишком много запросов' "$BODY"
check "$?" "0" "тело ответа с сообщением об ошибке"

step "POST /email/codes: 4 запроса (лимит 3/10мин)"
last=""
for i in $(seq 1 4); do
  last=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -H "Authorization: Bearer $token" \
    -o /dev/null -w "%{http_code}" -X POST "$BASE/email/codes")
done
check "$last" "429" "последний запрос выдачи кода заблокирован"

step "Эндпоинт без @RateLimit не ограничен"
check "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -w '%{http_code}' "$BASE/health")" "200" "health отвечает 200"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$FAIL"

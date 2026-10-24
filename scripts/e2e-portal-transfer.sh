#!/usr/bin/env bash
# E2E: передача владения порталом и история портала.
#
# Флоу: владелец инициирует передачу (своим паролем, причиной, выбором
# keepOldOwnerAsMember), предлагаемый владелец подтверждает (своим паролем
# и причиной) или отклоняет, владелец может отозвать до решения. Проверяет:
# PENDING/ACCEPTED/REJECTED/CANCELLED, доступ к активному запросу (владелец
# и предлагаемый — 200, посторонний — 404), смену владельца и участников,
# in-app и email по каждому событию, историю портала (участникам — 200,
# постороннему — 403), ошибки (409 второй запрос, 401 чужой пароль, 404
# чужой confirm) и события PORTAL_TRANSFER_* в email_log.
#
# Требования: поднятый стек (make dev-up), капча выключена
# (по умолчанию), docker для запросов к postgres, smtp4dev на localhost:3000.
# За прогон тратит лимит portal-transfer-request (5 initiate / 5 мин):
# повторный запуск до истечения окна упрётся в 429 — перезапустите
# приложение (make dev-restart) или подождите.
set -u
BASE=${BASE:-https://localhost:8443}
SMTP=${SMTP:-http://localhost:3000}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
# Windows Git Bash: inline-аргумент с не-ASCII портится (argv уходит в ANSI-кодировке), тело запроса
# перестаёт быть UTF-8 и сервер отвечает 400. Поэтому тела с кириллицей пишем в файл целиком.
BODY_FILE="$TMP/request-body.json"
body() { printf '%s' "$1" > "$BODY_FILE"; printf '@%s' "$BODY_FILE"; }
PASS=0; FAIL=0
check() { if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "  OK   $3 -> $2"; else FAIL=$((FAIL+1)); echo "  FAIL $3 (факт: $2, ожидалось: $1)"; fi; }
check_contains() { if printf '%s' "$2" | grep -q "$1"; then PASS=$((PASS+1)); echo "  OK   $3 содержит '$1'"; else FAIL=$((FAIL+1)); echo "  FAIL $3 (нет '$1' в: $(printf '%s' "$2" | head -c 300))"; fi; }
step() { echo; echo "── $1"; }
status() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null || echo ""; }

STAMP=$RANDOM$RANDOM
OWNER_EMAIL="owner-$STAMP@example.com"
PROPOSED_EMAIL="proposed-$STAMP@example.com"
STRANGER_EMAIL="stranger-$STAMP@example.com"
PASSWORD="Passw0rd!123"

# ── helpers ─────────────────────────────────────────
register() { # email last first jar
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -w '%{http_code}' -c "$4" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"firstName\":\"$3\",\"lastName\":\"$2\",\"email\":\"$1\",\"password\":\"$PASSWORD\"}"
}
confirm_email() { # email jar -> код из письма (ретраи, письмо приходит не мгновенно)
  local email="$1" jar="$2" msg="" code="" i
  for i in 1 2 3; do
    sleep 1
    msg=$(curl -s "$SMTP/api/Messages?limit=50" | python3 -c "
import sys,json
r=json.load(sys.stdin).get('results',[])
for m in r:
    if m.get('deliveredTo')==sys.argv[1] and m.get('subject')=='Подтверждение регистрации':
        print(m['id']); break
" "$email")
    [ -n "$msg" ] && break
  done
  code=$(curl -s "$SMTP/api/Messages/$msg/PlainText" | grep -oE -- '-?[0-9]{16,}' | head -1)
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -w '%{http_code}' -b "$jar" -X POST "$BASE/api/v1/email/confirmations/$code"
}
login() { # email jar
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -c "$2" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}"
}
last_mail_subject() { # email [subject] -> тема письма (или пусто); фильтр по теме не зависит от порядка
  curl -s "$SMTP/api/Messages?limit=100" | python3 -c "
import sys,json
subj=sys.argv[1]
r=json.load(sys.stdin).get('results',[])
for m in r:
    if m.get('deliveredTo')==sys.argv[2] and (not subj or m.get('subject')==subj):
        print(m['subject']); break
" "${2:-}" "$1"
}
notif_events() { # jar -> события из GET /notifications
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$1" "$BASE/api/v1/notifications?size=50" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for it in d.get('content',[]):
    print(it.get('event',''))
"
}
create_portal() { # jar name -> id
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$1" -X POST "$BASE/api/v1/portals" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"description\":\"e2e transfer\"}" | json "['id']"
}

# ── регистрация ─────────────────────────────────────
step "Регистрация трёх пользователей"
check 201 "$(register "$OWNER_EMAIL" Owner Alice "$TMP/owner.jar")" "register owner"
check 204 "$(confirm_email "$OWNER_EMAIL" "$TMP/owner.jar")" "confirm owner"
check 201 "$(register "$PROPOSED_EMAIL" Proposed Bob "$TMP/proposed.jar")" "register proposed"
check 204 "$(confirm_email "$PROPOSED_EMAIL" "$TMP/proposed.jar")" "confirm proposed"
check 201 "$(register "$STRANGER_EMAIL" Stranger Eve "$TMP/stranger.jar")" "register stranger"
check 204 "$(confirm_email "$STRANGER_EMAIL" "$TMP/stranger.jar")" "confirm stranger"
login "$OWNER_EMAIL" "$TMP/owner.jar"
login "$PROPOSED_EMAIL" "$TMP/proposed.jar"
login "$STRANGER_EMAIL" "$TMP/stranger.jar"

# ── сценарий 1: подтверждение ───────────────────────
step "Сценарий 1: initiate → confirm"
PORTAL=$(create_portal "$TMP/owner.jar" "Transfer Portal 1")
echo "  portalId=$PORTAL"
if [ -n "$PORTAL" ]; then PASS=$((PASS+1)); echo "  OK   portal создан"; else FAIL=$((FAIL+1)); echo "  FAIL portal не создан"; fi

HDR="$TMP/h1"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o "$TMP/init.json" -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PROPOSED_EMAIL\",\"password\":\"$PASSWORD\",\"reason\":\"Ухожу в отпуск\",\"keepOldOwnerAsMember\":true}"
check 201 "$(head -1 "$HDR" | awk '{print $2}')" "POST owner-transfer"
check "PENDING" "$(json "['status']" < "$TMP/init.json")" "статус PENDING"
EXPIRES=$(json "['expiresAt']" < "$TMP/init.json"); echo "  expiresAt=$EXPIRES"

check 200 "$(status -b "$TMP/owner.jar" "$BASE/api/v1/portals/$PORTAL/owner-transfer")" "GET transfer как владелец"
check 200 "$(status -b "$TMP/proposed.jar" "$BASE/api/v1/portals/$PORTAL/owner-transfer")" "GET transfer как предлагаемый"
check 404 "$(status -b "$TMP/stranger.jar" "$BASE/api/v1/portals/$PORTAL/owner-transfer")" "GET transfer постороннему → 404"

check_contains "PORTAL_TRANSFER_REQUESTED" "$(notif_events "$TMP/proposed.jar")" "in-app предлагаемому"
check_contains "Вам передают" "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/proposed.jar" "$BASE/api/v1/notifications?size=50" | python3 -c 'import sys,json;print(" ".join(i.get("title","") for i in json.load(sys.stdin).get("content",[])))')" "заголовок in-app"

check "Вам передают портал #$PORTAL" "$(last_mail_subject "$PROPOSED_EMAIL" "Вам передают портал #$PORTAL")" "email предлагаемому"
check "Передача портала #$PORTAL" "$(last_mail_subject "$OWNER_EMAIL" "Передача портала #$PORTAL")" "email участнику (владельцу)"

step "Ошибки при инициировании"
HDR="$TMP/h2"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o /dev/null -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PROPOSED_EMAIL\",\"password\":\"$PASSWORD\",\"reason\":\"x\",\"keepOldOwnerAsMember\":true}"
check 409 "$(head -1 "$HDR" | awk '{print $2}')" "второй активный запрос → 409"
HDR="$TMP/h3"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o /dev/null -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PROPOSED_EMAIL\",\"password\":\"wrong-pass-1\",\"reason\":\"x\",\"keepOldOwnerAsMember\":true}"
check 401 "$(head -1 "$HDR" | awk '{print $2}')" "чужой пароль владельца → 401"

step "Ошибки при подтверждении"
check 404 "$(status -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer/confirm" \
  -H 'Content-Type: application/json' -d "{\"password\":\"$PASSWORD\",\"reason\":\"ок\"}")" "confirm не предлагаемым → 404"
check 401 "$(status -b "$TMP/proposed.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer/confirm" \
  -H 'Content-Type: application/json' --data-binary "$(body '{"password":"wrong-pass-1","reason":"ок"}')")" "confirm неверным паролем → 401"
check 404 "$(status -b "$TMP/stranger.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer/confirm" \
  -H 'Content-Type: application/json' -d "{\"password\":\"$PASSWORD\",\"reason\":\"ок\"}")" "confirm посторонним → 404"

step "Подтверждение предлагаемым владельцем"
HDR="$TMP/h4"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o "$TMP/conf.json" -b "$TMP/proposed.jar" -X POST "$BASE/api/v1/portals/$PORTAL/owner-transfer/confirm" \
  -H 'Content-Type: application/json' \
  -d "{\"password\":\"$PASSWORD\",\"reason\":\"Принимаю, спасибо\"}"
check 200 "$(head -1 "$HDR" | awk '{print $2}')" "POST confirm"
check "ACCEPTED" "$(json "['status']" < "$TMP/conf.json")" "статус ACCEPTED"

check_contains "$PORTAL" "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/proposed.jar" "$BASE/api/v1/portals?size=100")" "портал в списке новых владельцев"
check "Портал #$PORTAL сменил владельца" "$(last_mail_subject "$OWNER_EMAIL" "Портал #$PORTAL сменил владельца")" "email старому владельцу"
check_contains "$PORTAL" "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/owner.jar" "$BASE/api/v1/portals/accessible?size=100")" "старый владелец остался участником (keepOld=true)"

step "История портала"
HIST=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/proposed.jar" "$BASE/api/v1/portals/$PORTAL/history?size=50")
check_contains "TRANSFER_REQUESTED" "$HIST" "история: TRANSFER_REQUESTED"
check_contains "TRANSFER_ACCEPTED" "$HIST" "история: TRANSFER_ACCEPTED"
check_contains "Принимаю, спасибо" "$HIST" "история: причина нового владельца"
check 200 "$(status -b "$TMP/owner.jar" "$BASE/api/v1/portals/$PORTAL/history")" "история видна старому владельцу (участнику)"
check 403 "$(status -b "$TMP/stranger.jar" "$BASE/api/v1/portals/$PORTAL/history")" "история постороннему → 403"

# ── сценарий 2: отклонение ──────────────────────────
step "Сценарий 2: initiate → reject"
PORTAL2=$(create_portal "$TMP/owner.jar" "Transfer Portal 2")
echo "  portalId=$PORTAL2"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL2/owner-transfer" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PROPOSED_EMAIL\",\"password\":\"$PASSWORD\",\"reason\":\"Передаю проект\",\"keepOldOwnerAsMember\":false}"
HDR="$TMP/h5"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o "$TMP/rej.json" -b "$TMP/proposed.jar" -X POST "$BASE/api/v1/portals/$PORTAL2/owner-transfer/reject" \
  -H 'Content-Type: application/json' --data-binary "$(body '{"reason":"Сейчас не могу взять"}')"
check 200 "$(head -1 "$HDR" | awk '{print $2}')" "POST reject"
check "REJECTED" "$(json "['status']" < "$TMP/rej.json")" "статус REJECTED"
check "Запрос на передачу портала #$PORTAL2 отклонён" "$(last_mail_subject "$OWNER_EMAIL" "Запрос на передачу портала #$PORTAL2 отклонён")" "email инициатору об отклонении"
check_contains "TRANSFER_REJECTED" "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/owner.jar" "$BASE/api/v1/portals/$PORTAL2/history?size=50")" "история: TRANSFER_REJECTED"
check 404 "$(status -b "$TMP/proposed.jar" "$BASE/api/v1/portals/$PORTAL2/owner-transfer")" "после решения GET → 404"

# ── сценарий 3: отзыв владельцем ────────────────────
step "Сценарий 3: initiate → cancel"
PORTAL3=$(create_portal "$TMP/owner.jar" "Transfer Portal 3")
echo "  portalId=$PORTAL3"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -o /dev/null -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL3/owner-transfer" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PROPOSED_EMAIL\",\"password\":\"$PASSWORD\",\"reason\":\"Временное предложение\",\"keepOldOwnerAsMember\":true}"
HDR="$TMP/h6"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -D "$HDR" -o "$TMP/can.json" -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL3/owner-transfer/cancel"
check 200 "$(head -1 "$HDR" | awk '{print $2}')" "POST cancel"
check "CANCELLED" "$(json "['status']" < "$TMP/can.json")" "статус CANCELLED"
check "Передача портала #$PORTAL3 отменена" "$(last_mail_subject "$PROPOSED_EMAIL" "Передача портала #$PORTAL3 отменена")" "email предлагаемому об отзыве"
check_contains "TRANSFER_CANCELLED" "$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$TMP/owner.jar" "$BASE/api/v1/portals/$PORTAL3/history?size=50")" "история: TRANSFER_CANCELLED (владелец)"
check 403 "$(status -b "$TMP/proposed.jar" "$BASE/api/v1/portals/$PORTAL3/history")" "история не-участнику → 403"
check 404 "$(status -b "$TMP/owner.jar" -X POST "$BASE/api/v1/portals/$PORTAL3/owner-transfer/cancel")" "повторный cancel → 404"

# ── журнал писем: события передачи ──────────────────
step "Журнал email_log: события PORTAL_TRANSFER_*"
EVENTS=$(docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tAc \
  "SELECT DISTINCT notification_event FROM email_log WHERE notification_event LIKE 'PORTAL_TRANSFER%' ORDER BY 1")
echo "  events=$EVENTS"
check_contains "PORTAL_TRANSFER_REQUESTED" "$EVENTS" "email_log: REQUESTED"
check_contains "PORTAL_TRANSFER_ACCEPTED" "$EVENTS" "email_log: ACCEPTED"
check_contains "PORTAL_TRANSFER_REJECTED" "$EVENTS" "email_log: REJECTED"
check_contains "PORTAL_TRANSFER_CANCELLED" "$EVENTS" "email_log: CANCELLED"


echo
if [ "$FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли (PASS=$PASS) ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки (PASS=$PASS FAIL=$FAIL) ═══"
fi
exit "$FAIL"

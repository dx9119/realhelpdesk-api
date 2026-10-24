#!/usr/bin/env bash
# E2E: in-app оповещения (SSE, long polling, прочтение, настройки, повторы).
#
# Проверяет: доверенный получает NEW_TICKET по long polling, пока владелец
# создаёт заявку; автор действия не получает уведомления о собственном
# действии; SSE-канал открывается комментарием connected и при подключении
# отдаёт backfill уже накопленных оповещений; после коммита новой заявки
# ожидающие будятся — уведомление приходит и по long polling, и по SSE;
# смена статуса уведомляет владельца; событие, отключённое в
# настройках, не приходит (пустой long polling отвечает 200 с content=[]
# по таймауту); чужое уведомление не читается (404); неизвестное событие
# в настройках — 400; без токена — 401.
#
# Повторы: проверяются только настройки (дефолт и сохранение). Фактическая
# доставка напоминаний — это планировщик с интервалом в минуту: он покрыт
# юнит-тестами, а в e2e добавлял ~5 минут ожидания без нового покрытия
# (механизм доставки тот же long polling/SSE, что проверяется выше).
# Прогон с планировщиком: E2E_REPEATS=1 bash scripts/e2e-notifications.sh
#
# Требования: поднятый стек (make dev-up), капча выключена (по
# умолчанию), docker для запросов к postgres. Регистрирует двух
# пользователей со временными email.
set -u

BASE=${BASE:-https://localhost:8443/api/v1}
TMP=$(mktemp -d)
# Windows Git Bash: inline-аргумент с не-ASCII портится (argv уходит в ANSI-кодировке), тело запроса
# перестаёт быть UTF-8 и сервер отвечает 400. Поэтому тела с кириллицей пишем в файл целиком.
BODY_FILE="$TMP/request-body.json"
body() { printf '%s' "$1" > "$BODY_FILE"; printf '@%s' "$BODY_FILE"; }
trap 'rm -rf "$TMP"' EXIT
JAR_A="$TMP/owner.jar"
JAR_B="$TMP/guest.jar"
BODY="$TMP/body.txt"
# docker compose exec вместо docker exec <container>: имя контейнера compose
# генерирует сам (<проект>-postgres-1) и меняется с переименованием проекта
PSQL="docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tA -c"
RUN=$(date +%s)
OWNER_EMAIL="notify-owner-$RUN@test.local"
GUEST_EMAIL="notify-guest-$RUN@test.local"

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
code() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -o "$BODY" -w "%{http_code}" "$@"; }
# непрочитанные из GET /notifications/unread-count
unread() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$1" "$BASE/notifications/unread-count" | grep -o '"count":[0-9]*' | cut -d: -f2; }
# максимальный id уведомлений пользователя (курсор для /wait)
last_id() {
  curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$1" "$BASE/notifications?size=50" | grep -o '"id":[0-9]*' | cut -d: -f2 | sort -n | tail -1
}
# цикл long polling до появления оповещения новее afterId: $1 jar, $2 afterId,
# $3 итераций по 30 сек (4 × 30 = 120 сек > интервал 1 мин + обход 1 мин).
# Используется только в ветке E2E_REPEATS=1 (проверка планировщика повторов).
# Печатает 1, если появилось, иначе 0; последний ответ — в $TMP/waitloop.json.
wait_new() {
  local jar=$1 after=$2 tries=${3:-5} i=0 code
  while [ "$i" -lt "$tries" ]; do
    code=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$jar" -o "$TMP/waitloop.json" -w "%{http_code}" \
      "$BASE/notifications/wait?timeoutSec=30&afterId=${after:-0}")
    if [ "$code" != "200" ]; then
      echo 0
      return
    fi
    if ! grep -q '"content":\[\]' "$TMP/waitloop.json"; then
      echo 1
      return
    fi
    i=$((i + 1))
  done
  echo 0
}
# ждёт появления паттерна в файле: $1 файл, $2 паттерн, $3 итераций по 1 сек.
# Печатает 1, если появилось, иначе 0.
await_grep() {
  local file=$1 pattern=$2 tries=${3:-5} i=0
  while [ "$i" -lt "$tries" ]; do
    if grep -q "$pattern" "$file" 2>/dev/null; then
      echo 1
      return
    fi
    sleep 1
    i=$((i + 1))
  done
  echo 0
}
# полный набор событий каталога in-app (для PUT preferences)
ALL_EVENTS='["NEW_TICKET","NEW_MESSAGE","NEW_SYSTEM_MESSAGE","CHANGE_TICKET","TICKET_DELETED","NEW_PORTAL","PORTAL_DELETED"]'

echo "═══ Оповещения ═══"

step "Регистрация владельца A и доверенного B"
s=$(code -c "$JAR_A" -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Alpha\",\"lastName\":\"Owner\",\"email\":\"$OWNER_EMAIL\",\"password\":\"Password123!\"}")
if [ "$s" = "429" ]; then
  echo "  FAIL лимит регистрации исчерпан — make dev-restart и повторите скрипт"
  exit 1
fi
check "$s" "201" "A зарегистрирован"
check "$(code -c "$JAR_B" -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Bravo\",\"lastName\":\"Guest\",\"email\":\"$GUEST_EMAIL\",\"password\":\"Password123!\"}")" \
  "201" "B зарегистрирован"
$PSQL "update users set is_email_verified=true;" >/dev/null

step "A создаёт портал и выдаёт B доступ"
check "$(code -b "$JAR_A" -X POST "$BASE/portals" -H 'Content-Type: application/json' \
  --data-binary "$(body "{\"name\":\"notify-portal-$RUN\",\"description\":\"оповещения\"}")")" "201" "портал создан"
PORTAL=$($PSQL "select p.id from portals p join users u on u.id=p.owner_id where u.email='$OWNER_EMAIL';")
GUEST_ID=$($PSQL "select id from users where email='$GUEST_EMAIL';")
check "$(code -b "$JAR_A" -X PUT "$BASE/portals/$PORTAL/settings/users" -H 'Content-Type: application/json' \
  -d "{\"userIds\":[\"$GUEST_ID\"]}")" "200" "B получил доступ"

step "Список и счётчик до событий"
check "$(code -b "$JAR_B" "$BASE/notifications")" "200" "GET /notifications доступен"
check "$(unread "$JAR_B")" "0" "у B непрочитанных нет"
check "$(code -b "$JAR_B" "$BASE/notifications/preferences")" "200" "GET /preferences доступен"

step "B ждёт long polling, пока A создаёт заявку"
curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" -o "$TMP/wait1.json" -w "%{http_code}" \
  "$BASE/notifications/wait?timeoutSec=15" > "$TMP/wait1.code" &
WAIT_PID=$!
sleep 1
check "$(code -b "$JAR_A" -X POST "$BASE/portals/$PORTAL/tickets" -H 'Content-Type: application/json' \
  --data-binary "$(body '{"title":"Первая заявка","body":"тело заявки"}')")" "201" "A создал заявку"
wait "$WAIT_PID"
check "$(cat "$TMP/wait1.code")" "200" "long polling B завершился 200"
if grep -q '"event":"NEW_TICKET"' "$TMP/wait1.json"; then
  echo "  OK   B получил NEW_TICKET по long polling"
else
  echo "  FAIL B не получил NEW_TICKET: $(cat "$TMP/wait1.json")"
  FAIL=1
fi
TICKET=$($PSQL "select id from ticket_model order by id desc limit 1;")

step "Автор действия не получает уведомления о своей заявке"
check "$(unread "$JAR_A")" "0" "у A непрочитанных нет (сам создал заявку)"
check "$(unread "$JAR_B")" "1" "у B одно непрочитанное"

step "B открывает SSE-канал: connected и backfill накопленного NEW_TICKET"
curl -sk -N -m 10 -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" \
  "$BASE/notifications/stream?afterId=0" > "$TMP/sse-backfill.txt" 2>/dev/null &
SSE_PID=$!
check "$(await_grep "$TMP/sse-backfill.txt" 'connected' 5)" "1" "SSE открылся комментарием connected"
check "$(await_grep "$TMP/sse-backfill.txt" '"event":"NEW_TICKET"' 5)" "1" "backfill при подключении отдал NEW_TICKET по SSE"
kill "$SSE_PID" 2>/dev/null || true
wait "$SSE_PID" 2>/dev/null || true

step "B читает всё, владелец получает CHANGE_TICKET от действия B"
check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/read-all")" "204" "B отметил всё прочитанным"
check "$(unread "$JAR_B")" "0" "у B снова 0"
check "$(code -b "$JAR_B" -X PUT "$BASE/portals/$PORTAL/tickets/$TICKET/status" -H 'Content-Type: application/json' \
  -d '{"status":"IN_PROGRESS"}')" "204" "B сменил статус заявки"
check "$(unread "$JAR_A")" "1" "у A уведомление о смене статуса (CHANGE_TICKET)"
check "$(unread "$JAR_B")" "0" "у B нет уведомления о собственном действии"
check "$(code -b "$JAR_A" -X PUT "$BASE/notifications/read-all")" "204" "A отметил всё прочитанным"

step "B отключает NEW_TICKET в настройках"
check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/preferences" -H 'Content-Type: application/json' \
  -d '{"events":["CHANGE_TICKET"]}')" "200" "настройки сохранены"
if curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" "$BASE/notifications/preferences" | grep -q '"CHANGE_TICKET"'; then
  echo "  OK   в настройках остался CHANGE_TICKET"
else
  echo "  FAIL настройки не сохранились"
  FAIL=1
fi

step "Отключённое событие не приходит: A создаёт вторую заявку"
AFTER=$(last_id "$JAR_B")
curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" -o "$TMP/wait2.json" -w "%{http_code}" \
  "$BASE/notifications/wait?timeoutSec=2&afterId=${AFTER:-0}" > "$TMP/wait2.code" &
WAIT_PID=$!
sleep 1
check "$(code -b "$JAR_A" -X POST "$BASE/portals/$PORTAL/tickets" -H 'Content-Type: application/json' \
  --data-binary "$(body '{"title":"Вторая заявка","body":"снова тело"}')")" "201" "A создал вторую заявку"
wait "$WAIT_PID"
check "$(cat "$TMP/wait2.code")" "200" "long polling B завершился 200 по таймауту"
if grep -q '"content":\[\]' "$TMP/wait2.json"; then
  echo "  OK   оповещение о NEW_TICKET не пришло (событие отключено)"
else
  echo "  FAIL ожидали пустой content: $(cat "$TMP/wait2.json")"
  FAIL=1
fi
check "$(unread "$JAR_B")" "0" "у B по-прежнему 0 непрочитанных"

step "B создаёт заявку — A (владелец) получает NEW_TICKET по long polling и SSE"
A_AFTER=$(last_id "$JAR_A")
curl -sk -N -m 10 -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_A" \
  "$BASE/notifications/stream?afterId=${A_AFTER:-0}" > "$TMP/sse-wake.txt" 2>/dev/null &
SSE_PID=$!
sleep 1
check "$(code -b "$JAR_B" -X POST "$BASE/portals/$PORTAL/tickets" -H 'Content-Type: application/json' \
  --data-binary "$(body '{"title":"Заявка от B","body":"от доверенного"}')")" "201" "B создал заявку"
check "$(await_grep "$TMP/sse-wake.txt" '"event":"NEW_TICKET"' 5)" "1" "A получил NEW_TICKET по SSE (будка после коммита)"
check "$(unread "$JAR_A")" "1" "у A непрочитанное NEW_TICKET"
kill "$SSE_PID" 2>/dev/null || true
wait "$SSE_PID" 2>/dev/null || true

step "Повторы: настройки по умолчанию у пользователя без строки"
PREFS=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_A" "$BASE/notifications/preferences")
if echo "$PREFS" | grep -q '"repeatEnabled":true' && echo "$PREFS" | grep -q '"repeatIntervalMinutes":30'; then
  echo "  OK   без строки настроек повтор включён на 30 минут"
else
  echo "  FAIL дефолт повтора не такой: $PREFS"
  FAIL=1
fi
check "$(code -b "$JAR_A" -X PUT "$BASE/notifications/read-all")" "204" "A дочитал хвост"

step "Повтор: напоминание о непрочитанной NEW_TICKET приходит каждую минуту"
check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/preferences" -H 'Content-Type: application/json' \
  -d "{\"events\":$ALL_EVENTS,\"repeatEnabled\":true,\"repeatIntervalMinutes\":1}")" "200" "повтор включён: каждую минуту"
PREFS=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" "$BASE/notifications/preferences")
if echo "$PREFS" | grep -q '"repeatEnabled":true' && echo "$PREFS" | grep -q '"repeatIntervalMinutes":1'; then
  echo "  OK   настройки повтора сохранились (вкл, 1 мин)"
else
  echo "  FAIL настройки повтора не сохранились: $PREFS"
  FAIL=1
fi
# Доставка напоминаний планировщиком (интервал 1 мин + обход раз в минуту)
# покрыта юнит-тестами; в e2e добавляла ~5 минут ожидания без нового покрытия
# (канал доставки тот же, что проверен выше). Прогон с планировщиком:
# E2E_REPEATS=1 bash scripts/e2e-notifications.sh
if [ "${E2E_REPEATS:-0}" = "1" ]; then
  check "$(unread "$JAR_B")" "0" "у B нет непрочитанных перед повтором"
  check "$(code -b "$JAR_A" -X POST "$BASE/portals/$PORTAL/tickets" -H 'Content-Type: application/json' \
    --data-binary "$(body '{"title":"Заявка для повтора","body":"напоминание"}')")" "201" "A создал заявку для повтора"
  check "$(unread "$JAR_B")" "1" "B получил исходное оповещение"
  AFTER=$(last_id "$JAR_B")
  check "$(wait_new "$JAR_B" "$AFTER" 4)" "1" "напоминание пришло по long polling"
  check "$(unread "$JAR_B")" "2" "непрочитанных стало 2: исходное + напоминание"
  REMINDER_ID=$(last_id "$JAR_B")
  check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/$REMINDER_ID/read")" "204" "напоминание отмечено прочитанным"
  check "$(unread "$JAR_B")" "0" "прочтение одной строки погасило всю группу"

  step "Повтор выключен — напоминания нет"
  check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/preferences" -H 'Content-Type: application/json' \
    -d "{\"events\":$ALL_EVENTS,\"repeatEnabled\":false,\"repeatIntervalMinutes\":1}")" "200" "повтор выключен"
  PREFS=$(curl -sk -H 'X-Requested-With: XMLHttpRequest' -b "$JAR_B" "$BASE/notifications/preferences")
  if echo "$PREFS" | grep -q '"repeatEnabled":false'; then
    echo "  OK   выключение сохранилось"
  else
    echo "  FAIL выключение не сохранилось: $PREFS"
    FAIL=1
  fi
  check "$(code -b "$JAR_A" -X POST "$BASE/portals/$PORTAL/tickets" -H 'Content-Type: application/json' \
    --data-binary "$(body '{"title":"Заявка без повтора","body":"проверка"}')")" "201" "A создал заявку"
  check "$(unread "$JAR_B")" "1" "B получил исходное оповещение"
  AFTER=$(last_id "$JAR_B")
  check "$(wait_new "$JAR_B" "$AFTER" 4)" "0" "напоминание не пришло за 2 минуты (повтор выключен)"
  check "$(unread "$JAR_B")" "1" "осталось только исходное оповещение"
fi

step "Права и валидация"
check "$(code "$BASE/notifications")" "401" "без токена — 401"
check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/999999/read")" "404" "чужое/несуществующее уведомление — 404"
check "$(code -b "$JAR_B" -X PUT "$BASE/notifications/preferences" -H 'Content-Type: application/json' \
  -d '{"events":["SOME_UNKNOWN_EVENT"]}')" "400" "неизвестное событие в настройках — 400"
check "$(code -b "$JAR_B" "$BASE/notifications/wait?timeoutSec=99")" "400" "timeoutSec вне 1..30 — 400"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$FAIL"

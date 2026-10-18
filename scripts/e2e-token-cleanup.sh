#!/usr/bin/env bash
# E2E: ротация refresh-токенов в БД — очистка отозванных и протухших строк jwt_tokens.
#
# Проверяет RefreshTokenCleanupService на живом стеке:
#   - POST /operator/tokens/cleanup доступен только ROLE_OPERATOR (401 / 403 / 200)
#   - отозванная строка младше jwt.revoked-token-retention-seconds не удаляется
#   - просроченные отозванные удаляются батчами (при размере батча 1 batches == revokedDeleted),
#     активные строки операторской очисткой не затрагиваются
#   - очистка идемпотентна: после прогона строк старше порога в БД не остаётся
#   - фоновое расписание (jwt.token-cleanup-cron) само удаляет отозванные по сроку хранения
#     и активные строки старше срока жизни refresh-токена, раньше срока не удаляет ничего
#   - два срока разнесены: отозванные удаляются очисткой, состаренные активные — остаются
#
# Сроки жизни токенов задаются в секундах, поэтому прогон переписывает infrastructure/.env:
# сроки занижаются до секунд (хранение отозванных 20 с, refresh 25 с), расписание — каждые
# 5 секунд, размер батча — 1, в APP_OPERATOR_EMAILS добавляется временный email (бутстрап
# выдаёт ему ROLE_OPERATOR). На время проверок ручного API расписание ставится «раз в год»,
# иначе оно перехватывает удалённые строки и проверки перестают быть детерминированными.
# Контейнер app пересоздаётся, а .env и роль оператора восстанавливаются по завершении,
# включая Ctrl+C.
#
# Требования: поднятый стек (make up), капча выключена (по умолчанию), docker для psql.
# Тратит рейт-лимиты /auth/register и /auth/login (по 10 на 5 мин); пересоздание app
# сбрасывает счётчики, поэтому прогон укладывается в лимит.
set -u

BASE=${BASE:-https://localhost:8443/api/v1}
ENV_FILE=${ENV_FILE:-infrastructure/.env}
COMPOSE="docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.override.yaml"
APP_COMPOSE="$COMPOSE --profile infra --profile local-tools --profile app"
PSQL="$COMPOSE --profile infra exec -T postgres psql -U user -d desk -tA -c"

# Измерительный конфиг: сроки в секундах, батч 1 (число батчей равно числу удалённых строк),
# cron из 6 полей — каждые 5 секунд. NEVER_CRON — 1 января 00:00: расписание фактически спит.
# Access-токен не укорачиваем: прогон идёт минуты, и проверяется им только доступ.
ACCESS_SECONDS=${ACCESS_E2E:-3600}
REFRESH_SECONDS=${REFRESH_E2E:-25}
RETENTION_SECONDS=${RETENTION_E2E:-20}
EVERY_5S_CRON=${CRON_E2E:-0/5 * * * * *}
NEVER_CRON="0 0 0 1 1 *"
# Сколько секунд ждать срабатывания расписания: срок (<=25 с) + тик cron (5 с) + запас
# на старт и БД. Приложение поднимается ~6 с, поэтому 90 с — с ~3-кратным запасом.
WAIT_SCHEDULED=${WAIT_SCHEDULED:-90}

TMP=$(mktemp -d)
JAR_OPER="$TMP/oper.jar"
JAR_USER="$TMP/user.jar"
BODY="$TMP/body.txt"
ENV_BACKUP="$TMP/env.backup"
LAST_COUNT=""
sql() { $PSQL "$1" | tail -1; }

RUN=$(date +%s)
PASS="Passw0rd!123"
OPER_EMAIL="e2e-cleanup-oper-$RUN@test.local"
USER_EMAIL="e2e-cleanup-user-$RUN@test.local"

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
  if [ "$1" -ge "$2" ] 2>/dev/null; then
    echo "  OK   $3"
  else
    echo "  FAIL $3 (факт: $1, ожидалось >= $2)"
    FAIL=1
  fi
}
code() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -o "$BODY" -w "%{http_code}" "$@"; }
json_num() { # json_num <поле> — числовое поле из последнего ответа
  tr -d ' \n\r' < "$BODY" | sed -n "s/.*\"$1\":\([0-9-]*\).*/\1/p"
}
read_env() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }
env_set() {
  if grep -q "^$1=" "$ENV_FILE"; then
    sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
  fi
}
rate_limited() { # rate_limited <код> <эндпоинт> — 429 после повала обрывает прогон
  if [ "$1" = "429" ]; then
    echo "  FAIL $2 -> 429: лимит исчерпан, сброс перезапуском не помог (счётчики в памяти приложения)"
    exit 1
  fi
}
reset_rate_limits() { # счётчики лимитов живут в памяти приложения — перезапуск их обнуляет
  restart_app
}
retry_429() { # retry_429 <код> — при 429 один раз перезапускает app и возвращает 0 (повторять запрос)
  [ "$1" = "429" ] || return 1
  reset_rate_limits || return 1
  return 0
}
register() { # register <фамилия> <email>
  local c
  c=$(code -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
    -d "{\"firstName\":\"Cleanup\",\"lastName\":\"$1\",\"email\":\"$2\",\"password\":\"$PASS\"}")
  retry_429 "$c" && c=$(code -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
    -d "{\"firstName\":\"Cleanup\",\"lastName\":\"$1\",\"email\":\"$2\",\"password\":\"$PASS\"}")
  rate_limited "$c" "/auth/register"
  check "$c" "201" "$1 зарегистрирован ($2)"
}
login_as() { # login_as <jar> <email> <описание>
  local c
  c=$(code -c "$1" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$2\",\"password\":\"$PASS\"}")
  retry_429 "$c" && c=$(code -c "$1" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$2\",\"password\":\"$PASS\"}")
  rate_limited "$c" "/auth/login"
  check "$c" "200" "$3"
}
cleanup() { # запрос операторской очистки: печатает код ответа, тело остаётся в $BODY
  code -b "$JAR_OPER" -X POST "$BASE/operator/tokens/cleanup"
}
count_tokens() { # count_tokens <user id> <условие по status>
  sql "select count(*) from jwt_tokens where user_id=$1 and $2;"
}
wait_count() { # wait_count <секунд> <user id> <условие> <ожидаемое> — ждёт нужного числа строк
  local timeout=$1 uid=$2 cond=$3 want=$4
  for _ in $(seq 1 "$timeout"); do
    LAST_COUNT=$(count_tokens "$uid" "$cond")
    [ "$LAST_COUNT" = "$want" ] && return 0
    sleep 1
  done
  return 1
}
age_tokens() { # age_tokens <user id> <условие> <интервал> — состаривает created_at (у сущности он immutable)
  sql "update jwt_tokens set created_at = now() - interval '$3' where user_id=$1 and $2;" >/dev/null
}
restart_app() {
  # Старый контейнер продолжает отвечать до полного пересоздания — ждём сначала
  # смены id контейнера, иначе health=200 от старого app провалит проверку ниже.
  local old_id
  old_id=$($APP_COMPOSE ps -q app 2>/dev/null || true)
  $APP_COMPOSE up -d --force-recreate --no-deps app >/dev/null 2>&1 || return 1
  for _ in $(seq 1 60); do
    [ -n "$old_id" ] && [ "$($APP_COMPOSE ps -q app 2>/dev/null)" = "$old_id" ] && sleep 1 && continue
    break
  done
  for _ in $(seq 1 90); do
    [ "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/health")" = "200" ] && return 0
    sleep 1
  done
  return 1
}

ENV_PATCHED=0
patch_env() {
  cp "$ENV_FILE" "$ENV_BACKUP"
  env_set JWT_ACCESS_TOKEN_EXPIRATION_SECONDS "$ACCESS_SECONDS"
  env_set JWT_REFRESH_TOKEN_EXPIRATION_SECONDS "$REFRESH_SECONDS"
  env_set JWT_REVOKED_TOKEN_RETENTION_SECONDS "$RETENTION_SECONDS"
  env_set JWT_TOKEN_CLEANUP_CRON "$NEVER_CRON"
  env_set JWT_TOKEN_CLEANUP_BATCH_MIN_SIZE 1
  env_set JWT_TOKEN_CLEANUP_BATCH_MAX_SIZE 1
  env_set JWT_TOKEN_CLEANUP_BATCH_PAUSE_MS 0
  local current
  current=$(read_env APP_OPERATOR_EMAILS)
  if [ -n "$current" ]; then
    env_set APP_OPERATOR_EMAILS "$current,$OPER_EMAIL"
  else
    env_set APP_OPERATOR_EMAILS "$OPER_EMAIL"
  fi
  ENV_PATCHED=1
}
restore_env() {
  [ "$ENV_PATCHED" = "1" ] || return 0
  ENV_PATCHED=0
  cp "$ENV_BACKUP" "$ENV_FILE"
  echo "  $ENV_FILE восстановлен, пересоздаю контейнер app..."
  restart_app || echo "  ВНИМАНИЕ: app не поднялся сам — проверьте 'make ps' и $ENV_FILE"
}
finish() {
  restore_env
  # Бутстрап роль только повышает, поэтому временного оператора снимаем вручную
  sql "update users set user_role='ROLE_USER' where email='$OPER_EMAIL';" >/dev/null 2>&1
  rm -rf "$TMP"
}
# restore_env пересоздаёт контейнер app, поэтому ловим и Ctrl+C, и штатное завершение
trap finish EXIT

echo "═══ Очистка отозванных и протухших refresh-токенов ═══"

if [ ! -f "$ENV_FILE" ]; then
  echo "  FAIL нет $ENV_FILE — выполните 'make env'"
  exit 1
fi
if [ "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/health")" != "200" ]; then
  echo "  FAIL $BASE/health недоступен — поднимите стек: make up"
  exit 1
fi
if [ "$(sql 'select 1;')" != "1" ]; then
  echo "  FAIL postgres недоступен через docker compose — поднимите стек: make up"
  exit 1
fi

step "Регистрация оператора и пользователя"
register Operator "$OPER_EMAIL"
register User "$USER_EMAIL"
# Бутстрап повышает до ROLE_OPERATOR только подтверждённые адреса
sql "update users set is_email_verified=true where email='$OPER_EMAIL';" >/dev/null
OPER_ID=$(sql "select id from users where email='$OPER_EMAIL';")
USER_ID=$(sql "select id from users where email='$USER_EMAIL';")
echo "  operator id=$OPER_ID, user id=$USER_ID"

step "Измерительный конфиг: хранение ${RETENTION_SECONDS} с, refresh ${REFRESH_SECONDS} с, батч 1, расписание спит"
patch_env
if ! restart_app; then
  echo "  FAIL app не поднялся с измерительным конфигом — $ENV_FILE будет восстановлен"
  exit 1
fi

step "Вход после перезапуска"
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл"
login_as "$JAR_OPER" "$OPER_EMAIL" "оператор вошёл (бутстрап выдал ROLE_OPERATOR)"

step "Доступ к POST /operator/tokens/cleanup"
check "$(code -X POST "$BASE/operator/tokens/cleanup")" "401" "без access-токена"
check "$(code -b "$JAR_USER" -X POST "$BASE/operator/tokens/cleanup")" "403" "с ролью ROLE_USER"
check "$(cleanup)" "200" "с ролью ROLE_OPERATOR"

step "Срок хранения: свежий отозванный токен остаётся в БД"
check "$(code -b "$JAR_USER" -X DELETE "$BASE/auth/session")" "204" "пользователь вышел (refresh → LOGOUT)"
LOGOUT_ROWS=$(count_tokens "$USER_ID" "status='LOGOUT'")
check "$LOGOUT_ROWS" "1" "отозванная строка появилась"
check "$(cleanup)" "200" "очистка отработала"
check "$(count_tokens "$USER_ID" "status='LOGOUT'")" "$LOGOUT_ROWS" "строка младше ${RETENTION_SECONDS} с не удалена"

step "Просроченные отозванные удаляются батчами, активные не затрагиваются"
login_as "$JAR_USER" "$USER_EMAIL" "вход после выхода (прошлый refresh → REVOKED)"
check "$(code -b "$JAR_USER" -X DELETE "$BASE/auth/session")" "204" "пользователь вышел (refresh → LOGOUT)"
login_as "$JAR_USER" "$USER_EMAIL" "вход с активной сессией"
age_tokens "$USER_ID" "status <> 'ACTIVE'" "1 hour"
AGED=$(count_tokens "$USER_ID" "status <> 'ACTIVE'")
ACTIVE_BEFORE=$(count_tokens "$USER_ID" "status='ACTIVE'")
echo "  состарено отозванных: $AGED, активных: $ACTIVE_BEFORE"
check_ge "$AGED" "2" "есть что удалять несколькими батчами"
check_ge "$ACTIVE_BEFORE" "1" "есть что сохранить"
check "$(cleanup)" "200" "очистка отозванных"
DELETED=$(json_num revokedDeleted)
BATCHES=$(json_num batches)
echo "  ответ: revokedDeleted=$DELETED, batches=$BATCHES"
check_ge "$DELETED" "$AGED" "удалены все состаренные отозванные"
check "$BATCHES" "$DELETED" "каждая строка удалена отдельным батчем (размер батча 1)"
check "$(count_tokens "$USER_ID" "status <> 'ACTIVE'")" "0" "отозванные строки пользователя удалены"
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "$ACTIVE_BEFORE" "активные строки не тронуты"

step "Повторная очистка ничего не удаляет"
check "$(cleanup)" "200" "повторная очистка отработала"
check "$(count_tokens "$USER_ID" "status <> 'ACTIVE'")" "0" "отозванные строки пользователя не появились снова"
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "$ACTIVE_BEFORE" "активные строки целы"

step "Расписание включено (каждые 5 с): отозванные удаляются по сроку хранения"
env_set JWT_TOKEN_CLEANUP_CRON "$EVERY_5S_CRON"
if ! restart_app; then
  echo "  FAIL app не поднялся с расписанием каждые 5 с — $ENV_FILE будет восстановлен"
  exit 1
fi
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл"
check "$(code -b "$JAR_USER" -X DELETE "$BASE/auth/session")" "204" "пользователь вышел (refresh → LOGOUT)"
if wait_count "$WAIT_SCHEDULED" "$USER_ID" "status <> 'ACTIVE'" "0"; then
  echo "  OK   отозванная строка удалена фоновой задачей (порог ${RETENTION_SECONDS} с)"
else
  echo "  FAIL отозванная строка осталась (в БД: $LAST_COUNT, ожидалось 0 за ${WAIT_SCHEDULED} с)"
  FAIL=1
fi

step "Расписание: активные строки живут свой срок (${REFRESH_SECONDS} с)"
if wait_count "$WAIT_SCHEDULED" "$USER_ID" "status='ACTIVE'" "0"; then
  echo "  OK   остатки прошлых сессий удалены — в БД нет активных строк пользователя"
else
  echo "  FAIL активные строки не удалены (в БД: $LAST_COUNT, ожидалось 0 за ${WAIT_SCHEDULED} с)"
  FAIL=1
fi
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл (свежий активный refresh)"
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "выдана одна активная строка"
sleep 8
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "прошло 8 с (< ${REFRESH_SECONDS} с) — строка ещё жива"
if wait_count "$WAIT_SCHEDULED" "$USER_ID" "status='ACTIVE'" "0"; then
  echo "  OK   протухшая активная строка удалена фоновой задачей"
else
  echo "  FAIL протухшая строка осталась (в БД: $LAST_COUNT, ожидалось 0 за ${WAIT_SCHEDULED} с)"
  FAIL=1
fi
check_ge "$($APP_COMPOSE logs --since 15m app 2>/dev/null | grep -c 'протухшие активные')" "1" \
  "прогоны расписания видны в логах приложения"

echo
echo "── Восстановление штатного конфига"
restore_env

step "Два срока разнесены: отозванные удаляются, состаренные активные остаются"
RETENTION_NOW=$(read_env JWT_REVOKED_TOKEN_RETENTION_SECONDS)
REFRESH_NOW=$(read_env JWT_REFRESH_TOKEN_EXPIRATION_SECONDS)
if [ "${RETENTION_NOW:-0}" -lt 3600 ] || [ "${REFRESH_NOW:-0}" -lt 3600 ]; then
  echo "  SKIP в $ENV_FILE сроки меньше часа ($RETENTION_NOW/$REFRESH_NOW с) — проверка потеряет смысл"
else
  login_as "$JAR_USER" "$USER_EMAIL" "первый вход"
  login_as "$JAR_USER" "$USER_EMAIL" "второй вход (прошлый refresh → REVOKED)"
  # 400 дней — больше и срока хранения отозванных, и срока жизни refresh-токена
  age_tokens "$USER_ID" "true" "400 days"
  check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "есть состаренная активная строка"
  check "$(cleanup)" "200" "очистка отозванных"
  check "$(count_tokens "$USER_ID" "status <> 'ACTIVE'")" "0" "состаренный отозванный удалён"
  check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "состаренный активный ждёт очистки по сроку токена"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$FAIL"

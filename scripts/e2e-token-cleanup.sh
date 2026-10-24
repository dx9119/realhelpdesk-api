#!/usr/bin/env bash
# E2E: фоновая очистка refresh-токенов по cron-расписанию.
#
# Проверяет RefreshTokenCleanupService на живом стеке — только то, что не
# покрывают автотесты (фоновые таймеры нельзя проверить без реального
# расписания):
#   - расписание (jwt.token-cleanup-cron) само удаляет отозванные строки по
#     сроку хранения и активные строки старше срока жизни refresh-токена
#   - раньше срока расписание не удаляет ничего (свежая активная строка
#     переживает несколько тиков)
#
# Доступ к /operator/tokens/cleanup (401/403/200), срок хранения отозванных,
# батчи, идемпотентность и разнесение двух сроков покрыты
# OperatorTokenCleanupIT и RefreshTokenCleanupServiceTest — здесь не повторяются.
#
# Измерительный конфиг передаётся инлайном при пересоздании контейнера app
# (переменные окружения хоста перекрывают infrastructure/.env, а сроки и cron
# объявлены в services.app.environment compose-файла с дефолтами): файл .env
# скрипт не читает на запись и не меняет вовсе, поэтому прерванный прогон
# (Ctrl+C, kill -9) не оставляет сломанной конфигурации. По завершении app
# пересоздаётся без инлайн-переменных — возвращается штатное расписание.
#
# Требования: поднятый стек (make dev-up), капча выключена (по умолчанию), docker
# для psql. Тратит рейт-лимиты /auth/register и /auth/login (по 10 на 5 мин);
# пересоздание app сбрасывает счётчики, поэтому прогон укладывается в лимит.
set -u

BASE=${BASE:-https://localhost:8443/api/v1}
ENV_FILE=${ENV_FILE:-infrastructure/.env}
COMPOSE="docker compose -f infrastructure/docker-compose.yaml -f infrastructure/docker-compose.override.yaml"
APP_COMPOSE="$COMPOSE --profile infra --profile local-tools --profile app"
PSQL="$COMPOSE --profile infra exec -T postgres psql -U user -d desk -tA -c"

# Измерительный конфиг: сроки в секундах; cron из 6 полей — каждые 5 секунд.
# NEVER_CRON — 1 января 00:00: расписание фактически спит (нужно, чтобы
# подготовить данные, не дав таймеру их перехватить).
# Access-токен не укорачиваем: прогон идёт минуты, и проверяется им только доступ.
ACCESS_SECONDS=${ACCESS_E2E:-3600}
REFRESH_SECONDS=${REFRESH_E2E:-15}
RETENTION_SECONDS=${RETENTION_E2E:-10}
EVERY_5S_CRON=${CRON_E2E:-0/5 * * * * *}
NEVER_CRON="0 0 0 1 1 *"
# Сколько секунд ждать срабатывания расписания: срок (<=15 с) + тик cron (5 с)
# + запас на старт и БД. Приложение поднимается ~6-15 с, поэтому 45 с — с
# ~2-кратным запасом.
WAIT_SCHEDULED=${WAIT_SCHEDULED:-45}

TMP=$(mktemp -d)
JAR_USER="$TMP/user.jar"
BODY="$TMP/body.txt"
LAST_COUNT=""
sql() { $PSQL "$1" | tail -1; }

RUN=$(date +%s)
PASS="Passw0rd!123"
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
code() { curl -sk -H 'X-Requested-With: XMLHttpRequest' -o "$BODY" -w "%{http_code}" "$@"; }
register() { # register <email>
  local c
  c=$(code -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
    -d "{\"firstName\":\"Cleanup\",\"lastName\":\"User\",\"email\":\"$1\",\"password\":\"$PASS\"}")
  check "$c" "201" "пользователь зарегистрирован ($1)"
}
login_as() { # login_as <jar> <email> <описание>
  local c
  c=$(code -c "$1" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$2\",\"password\":\"$PASS\"}")
  check "$c" "200" "$3"
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
# wait_both <секунд> <user id> <ожидаемое для отозванных> <ожидаемое для активных> —
# ждёт одновременного выполнения обоих условий. Один тик cron удаляет и отозванные
# по сроку хранения, и протухшие активные по сроку токена, поэтому ждать их раздельно
# нет смысла — общая проверка вдвое быстрее и строже (снимок в один момент).
wait_both() {
  local timeout=$1 uid=$2 want_rev=$3 want_act=$4
  for _ in $(seq 1 "$timeout"); do
    LAST_REVOKED=$(count_tokens "$uid" "status <> 'ACTIVE'")
    LAST_ACTIVE=$(count_tokens "$uid" "status='ACTIVE'")
    [ "$LAST_REVOKED" = "$want_rev" ] && [ "$LAST_ACTIVE" = "$want_act" ] && return 0
    sleep 1
  done
  return 1
}
# up_app <переменные окружения...> — пересоздать только контейнер app и дождаться
# health. Переданные переменные действуют в рамках команды: compose подставляет их
# в интерполяцию, infrastructure/.env не меняется.
# Профиль приложения переносится из работающего контейнера: SPRING_PROFILES_ACTIVE
# задаётся не в .env, а инлайном вызывающей make-целью (dev-profile-local → dev), и
# без этого пересоздание вернуло бы compose-дефолт (dev,keycloak) — сценарий
# работает во внутреннем JWT-контуре и на нём бы упал.
up_app() {
  local old_id profile
  profile=$(current_profile)
  old_id=$($APP_COMPOSE ps -q app 2>/dev/null || true)
  env "SPRING_PROFILES_ACTIVE=$profile" "$@" \
    $APP_COMPOSE up -d --force-recreate --no-deps app >/dev/null 2>&1 || return 1
  # Старый контейнер продолжает отвечать до полного пересоздания — ждём сначала
  # смены id, иначе health=200 от старого app провалит проверку ниже.
  for _ in $(seq 1 30); do
    [ -n "$old_id" ] && [ "$($APP_COMPOSE ps -q app 2>/dev/null)" = "$old_id" ] && sleep 1 && continue
    break
  done
  for _ in $(seq 1 45); do
    [ "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/health")" = "200" ] && return 0
    sleep 1
  done
  return 1
}
# current_profile — SPRING_PROFILES_ACTIVE запущенного контейнера app (внутри
# контейнера переменная уже развёрнута из инлайн-значения или compose-дефолта).
current_profile() {
  $APP_COMPOSE exec -T app printenv SPRING_PROFILES_ACTIVE 2>/dev/null | tr -d '\r' || true
}
# Измерительные тайминги: те же ключи, что объявлены в services.app.environment
# compose-файла, — переменные хоста перекрывают значения из infrastructure/.env.
MEASURE_ENV=(
  "JWT_ACCESS_TOKEN_EXPIRATION_SECONDS=$ACCESS_SECONDS"
  "JWT_REFRESH_TOKEN_EXPIRATION_SECONDS=$REFRESH_SECONDS"
  "JWT_REVOKED_TOKEN_RETENTION_SECONDS=$RETENTION_SECONDS"
)
# restart_measured <cron> — app с измерительными сроками и заданным расписанием
restart_measured() { up_app "${MEASURE_ENV[@]}" "JWT_TOKEN_CLEANUP_CRON=$1"; }
# restore_app — app со штатным конфигом (без инлайн-переменных)
restore_app() { up_app; }

# Прогон оставляет app с измерительным конфигом — на выходе возвращаем штатный
# (пересоздание контейнера; сам infrastructure/.env не менялся).
FINISHED=0
finish() {
  rm -rf "$TMP"
  [ "$FINISHED" = "1" ] && return 0
  echo "  Пересоздаю app со штатным конфигом..."
  restore_app || echo "  ВНИМАНИЕ: app не поднялся сам — проверьте 'make dev-ps' и выполните 'make dev-restart'"
}
trap finish EXIT

echo "═══ Фоновая очистка refresh-токенов по расписанию ═══"

if [ ! -f "$ENV_FILE" ]; then
  echo "  FAIL нет $ENV_FILE — выполните 'make env'"
  exit 1
fi
if [ "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/health")" != "200" ]; then
  echo "  FAIL $BASE/health недоступен — поднимите стек: make dev-up"
  exit 1
fi
if [ "$(sql 'select 1;')" != "1" ]; then
  echo "  FAIL postgres недоступен через docker compose — поднимите стек: make dev-up"
  exit 1
fi

step "Измерительный конфиг: хранение ${RETENTION_SECONDS} с, refresh ${REFRESH_SECONDS} с, расписание спит"
register "$USER_EMAIL"
USER_ID=$(sql "select id from users where email='$USER_EMAIL';")
echo "  user id=$USER_ID"
if ! restart_measured "$NEVER_CRON"; then
  echo "  FAIL app не поднялся с измерительным конфигом"
  exit 1
fi
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл"

step "Расписание включено (каждые 5 с): отозванные и протухшие активные удаляются за один тик"
# Активная строка с прошлого шага к этому моменту уже старше ${REFRESH_SECONDS} с
# (пересоздание app занимает больше), свежий выход даёт отозванную строку.
# Один тик cron чистит обе категории, поэтому ждём их одновременно — это и
# быстрее, и строже отдельного ожидания (снимок БД в один момент).
if ! restart_measured "$EVERY_5S_CRON"; then
  echo "  FAIL app не поднялся с расписанием каждые 5 с"
  exit 1
fi
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл"
check "$(code -b "$JAR_USER" -X DELETE "$BASE/auth/session")" "204" "пользователь вышел (refresh → LOGOUT)"
if wait_both "$WAIT_SCHEDULED" "$USER_ID" "0" "0"; then
  echo "  OK   отозванная строка удалена фоновой задачей (порог ${RETENTION_SECONDS} с)"
  echo "  OK   протухшая активная строка удалена фоновой задачей"
else
  echo "  FAIL расписание не почистило строки (в БД: отозванных $LAST_REVOKED/0, активных $LAST_ACTIVE/0 за ${WAIT_SCHEDULED} с)"
  FAIL=1
fi

step "Расписание: активные строки живут свой срок (${REFRESH_SECONDS} с), раньше не удаляются"
login_as "$JAR_USER" "$USER_EMAIL" "пользователь вошёл (свежий активный refresh)"
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "выдана одна активная строка"
sleep 3
check "$(count_tokens "$USER_ID" "status='ACTIVE'")" "1" "прошло 3 с (< ${REFRESH_SECONDS} с, тики cron уже шли) — строка жива"
if wait_count 20 "$USER_ID" "status='ACTIVE'" "0"; then
  echo "  OK   протухшая активная строка удалена фоновой задачей"
else
  echo "  FAIL протухшая строка осталась (в БД: $LAST_COUNT, ожидалось 0 за 20 с)"
  FAIL=1
fi
# Проверяем, что хотя бы одно вхождение есть (>= 1, а не ровно 1)
LOG_MATCHES=$($APP_COMPOSE logs --since 15m app 2>/dev/null | grep -c 'протухшие активные' || true)
if [ "$LOG_MATCHES" -ge 1 ]; then
  echo "  OK   прогоны расписания видны в логах приложения ($LOG_MATCHES)"
else
  echo "  FAIL прогоны расписания не видны в логах приложения (факт: $LOG_MATCHES, ожидалось: >= 1)"
  FAIL=1
fi

echo
echo "── Восстановление штатного конфига"
restore_app
FINISHED=1

echo
if [ "$FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ═══"
else
  echo "═══ ИТОГ: есть проваленные проверки ═══"
fi
exit "$FAIL"

#!/usr/bin/env bash
# E2E: база знаний через Keycloak (Bearer).
#
# Проверяет контур внешнего выпуска токенов на живом стеке:
#   - без токена эндпоинты базы знаний отвечают 401;
#   - справочник категорий меняет только ROLE_OPERATOR (POST/PUT/PATCH/DELETE),
#     обычный пользователь получает 403, чтение доступно всем аутентифицированным;
#   - имя категории уникально без учёта регистра (409), устаревший If-Match — 412;
#   - глобальную статью (portalId: null) создаёт только оператор;
#   - статью портала создаёт, меняет и удаляет владелец или доверенный
#     пользователь портала (allowedUserIds); отзыв доступа действует сразу;
#   - посторонний не создаёт, не читает и не удаляет статью чужого портала;
#   - поиск находит статью по подстроке вопроса и ответа (кириллица, без учёта регистра);
#   - статья приватного портала не видна в общей выдаче постороннему и не читается по id;
#   - мягкое удаление убирает статью из выдачи, удаление используемой категории — 409.
#
# Требования: приложение на хосте (make run-local),
# SPRING_PROFILES_ACTIVE=dev,host (make run-local), docker для psql.
# Перед прогоном удаляет артефакты прошлых прогонов (категории, статьи и портал
# с префиксом kc-kb) — прогон идемпотентен.
# Использует существующих пользователей realm:
#   user@example.com / user-pass
#   operator@example.com / operator-pass

set -u

BASE=${BASE:-http://localhost:8080/api/v1}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
REALM=${REALM:-realhelpdesk}
CLIENT=${CLIENT:-realhelpdesk-api}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PSQL="docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres psql -U user -d desk -tA -c"

COUNT_FAIL=0
COUNT_PASS=0

# Префикс артефактов: по нему чистим БД перед прогоном.
KB="kc-kb"

step() { echo; echo "── $1"; }
check() { # check <фактический код> <ожидаемый код> <описание>
  if [ "$1" = "$2" ]; then
    COUNT_PASS=$((COUNT_PASS + 1))
    echo "  OK   $3 -> $2"
  else
    COUNT_FAIL=$((COUNT_FAIL + 1))
    echo "  FAIL $3 (факт: $1, ожидалось: $2)"
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

kb_code() { # kb_code <token> <endpoint> [method] [body] [if-match]
  local token="$1" endpoint="$2" method="${3:-GET}" body="${4:-}" ifmatch="${5:-}"
  local args=(-sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token")
  [ "$method" != "GET" ] && args+=(-X "$method")
  [ -n "$body" ] && args+=("-H" "Content-Type: application/json" -d "$body")
  [ -n "$ifmatch" ] && args+=("-H" "If-Match: $ifmatch")
  curl "${args[@]}" "$BASE$endpoint"
}

kb_get() { # kb_get <token> <endpoint> — GET с Bearer, возвращает тело
  curl -sk -H "Authorization: Bearer $1" "$BASE$2"
}

kb_post() { # kb_post <token> <endpoint> <body> — возвращает "статус|тело"
  local resp status body
  resp=$(curl -sk -w '\n%{http_code}' -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$3" -X POST "$BASE$2")
  status=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  echo "${status}|${body}"
}

kb_etag() { # kb_etag <token> <endpoint> — ETag из заголовков ответа
  curl -sk -D - -o /dev/null -H "Authorization: Bearer $1" "$BASE$2" \
    | tr -d '\r' | awk 'tolower($1)=="etag:"{print $2}'
}

json_id() { # json_id <json> — поле id
  printf '%s' "$1" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo ""
}

# page_has_id <json> <id> — есть ли статья с таким id в content страницы.
# Сравнение по полю, а не подстрокой: id «6» иначе совпал бы с датой createdAt.
page_has_id() {
  printf '%s' "$1" | python3 -c "
import json, sys
page = json.load(sys.stdin)
print('yes' if sys.argv[1] in {str(item.get('id')) for item in page.get('content', [])} else 'no')
" "$2" 2>/dev/null || echo "no"
}

# require_id <id> <описание> — без id дальше идти нельзя: пустая строка в grep совпала бы со всем
require_id() {
  if [ -z "$1" ]; then
    echo "  FAIL не удалось получить id: $2"
    exit 1
  fi
}

# ── Готовность стека ──────────────────────────────────────────

step "Готовность Keycloak и realm $REALM"
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration")" != "200" ]; then
  echo "  FAIL Keycloak недоступен на $KEYCLOAK — поднимите стек: make dev-run"
  exit 1
fi
echo "  OK   discovery realm'а доступен"

USER_EMAIL="user@example.com"
USER_PASS="user-pass"
OPER_EMAIL="operator@example.com"
OPER_PASS="operator-pass"

USER_TOKEN=$(kc_token "$USER_EMAIL" "$USER_PASS")
[ -n "$USER_TOKEN" ] || { echo "  FAIL токен $USER_EMAIL не выдан"; exit 1; }
echo "  OK   токен $USER_EMAIL получен"

OPER_TOKEN=$(kc_token "$OPER_EMAIL" "$OPER_PASS")
[ -n "$OPER_TOKEN" ] || { echo "  FAIL токен $OPER_EMAIL не выдан"; exit 1; }
echo "  OK   токен $OPER_EMAIL получен"

# ── Очистка артефактов прошлых прогонов ───────────────────────

step "Очистка артефактов прошлых прогонов"
# Статьи удаляются раньше категорий (FK category_id), категории — раньше портала.
if $PSQL "delete from knowledgebase_articles where lower(question) like '$KB%';
delete from knowledgebase_categories where lower(name) like '$KB%';
delete from notifications where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email in ('$USER_EMAIL','$OPER_EMAIL') and p.name like '$KB%');
delete from portal_history where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email in ('$USER_EMAIL','$OPER_EMAIL') and p.name like '$KB%');
delete from portal_access where portal_id in (select p.id from portals p join users u on u.id=p.owner_id where u.email in ('$USER_EMAIL','$OPER_EMAIL') and p.name like '$KB%');
delete from portals where owner_id in (select id from users where email in ('$USER_EMAIL','$OPER_EMAIL')) and name like '$KB%';" >/dev/null 2>&1; then
  echo "  OK   артефакты базы знаний удалены"
else
  echo "  FAIL очистка БД не удалась — postgres поднят? (make dev-run)"
  exit 1
fi

# ── Доступ без токена ─────────────────────────────────────────

step "Доступ без токена"
check "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/knowledgebase/articles")" "401" "GET /knowledgebase/articles без токена"
check "$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/knowledgebase/categories")" "401" "GET /knowledgebase/categories без токена"
check "$(curl -sk -o /dev/null -w '%{http_code}' -X POST "$BASE/knowledgebase/categories" \
  -H 'Content-Type: application/json' -d '{"name":"x"}')" "401" "POST /knowledgebase/categories без токена"

# ── Справочник категорий ──────────────────────────────────────

step "Категории: права и уникальность имени"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/categories" "POST" "{\"name\":\"$KB Оплата\"}")" "403" "POST категории пользователем без ROLE_OPERATOR"

CAT_RESP=$(kb_post "$OPER_TOKEN" "/knowledgebase/categories" \
  "{\"name\":\"$KB Оплата\",\"description\":\"Способы оплаты и счета\"}")
check "${CAT_RESP%%|*}" "201" "POST категории оператором"
CAT_BODY="${CAT_RESP#*|}"
CAT_ID=$(json_id "$CAT_BODY")
echo "  categoryId=$CAT_ID"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories" "POST" "{\"name\":\"$KB оплата\"}")" "409" "POST категории с именем в другом регистре"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/categories/$CAT_ID" "PUT" "{\"name\":\"$KB Оплата\",\"description\":\"x\"}")" "403" "PUT категории пользователем"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID" "PUT" \
  "{\"name\":\"$KB Оплата\",\"description\":\"Способы оплаты и счета\"}" '"kb-category-999"')" "412" "PUT категории с устаревшим If-Match"

CAT_ETAG=$(kb_etag "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID")
check_contains "kb-category-" "$CAT_ETAG" "ETag категории"

CAT_LIST=$(kb_get "$USER_TOKEN" "/knowledgebase/categories")
check_contains "$KB Оплата" "$CAT_LIST" "справочник категорий для обычного пользователя"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID" "PATCH" \
  '{"description":"Способы оплаты, счета и возвраты"}' "$CAT_ETAG")" "200" "PATCH категории с актуальным If-Match"

# ── Глобальные статьи ─────────────────────────────────────────

step "Глобальные статьи: только оператор"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles" "POST" \
  "{\"question\":\"$KB Как оплатить?\",\"answer\":\"Картой на странице оплаты.\",\"categoryId\":$CAT_ID,\"portalId\":null}")" \
  "403" "POST глобальной статьи пользователем"

ART_RESP=$(kb_post "$OPER_TOKEN" "/knowledgebase/articles" \
  "{\"question\":\"$KB Как оплатить заказ?\",\"answer\":\"Картой или по счёту на странице оплаты.\",\"categoryId\":$CAT_ID,\"portalId\":null}")
check "${ART_RESP%%|*}" "201" "POST глобальной статьи оператором"
ART_BODY="${ART_RESP#*|}"
ART_ID=$(json_id "$ART_BODY")
echo "  articleId=$ART_ID"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles" "POST" \
  "{\"question\":\"$KB как оплатить заказ?\",\"answer\":\"Дубль.\",\"categoryId\":$CAT_ID,\"portalId\":null}")" \
  "409" "POST статьи с тем же вопросом в другом регистре"

ART_ETAG=$(kb_etag "$OPER_TOKEN" "/knowledgebase/articles/$ART_ID")
check_contains "kb-article-" "$ART_ETAG" "ETag статьи"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles/$ART_ID" "PUT" \
  "{\"question\":\"$KB Как оплатить заказ?\",\"answer\":\"Картой или по счёту.\",\"categoryId\":$CAT_ID,\"portalId\":null}" \
  '"kb-article-999"')" "412" "PUT статьи с устаревшим If-Match"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles/$ART_ID" "PATCH" \
  '{"answer":"Картой, по счёту или через СБП."}' "$ART_ETAG")" "200" "PATCH статьи с актуальным If-Match"

step "Поиск по вопросу и ответу (кириллица, без учёта регистра)"
SEARCH_Q=$(kb_get "$USER_TOKEN" "/knowledgebase/articles?search=$(python3 -c "import urllib.parse;print(urllib.parse.quote('как ОПЛАТИТЬ заказ'))")")
check "$(page_has_id "$SEARCH_Q" "$ART_ID")" "yes" "поиск по подстроке вопроса"
SEARCH_A=$(kb_get "$USER_TOKEN" "/knowledgebase/articles?search=$(python3 -c "import urllib.parse;print(urllib.parse.quote('через сбп'))")")
check "$(page_has_id "$SEARCH_A" "$ART_ID")" "yes" "поиск по подстроке ответа"
check "$(page_has_id "$(kb_get "$USER_TOKEN" "/knowledgebase/articles?categoryId=$CAT_ID")" "$ART_ID")" "yes" "фильтр по категории"

# ── Статьи портала ────────────────────────────────────────────

step "Статьи портала: владелец и посторонний"
# Портал нужен ради приватного контура: у публичного статью прочитал бы любой.
PORTAL_RESP=$(kb_post "$USER_TOKEN" "/portals" "{\"name\":\"$KB-portal\",\"description\":\"портал базы знаний\"}")
check "${PORTAL_RESP%%|*}" "201" "POST /portals владельцем"
PORTAL_ID=$(json_id "${PORTAL_RESP#*|}")
require_id "$PORTAL_ID" "portalId"
echo "  portalId=$PORTAL_ID"

PORTAL_RESP2=$(kb_post "$USER_TOKEN" "/knowledgebase/articles" \
  "{\"question\":\"$KB Как вернуть деньги?\",\"answer\":\"Напишите в поддержку портала.\",\"categoryId\":$CAT_ID,\"portalId\":$PORTAL_ID}")
check "${PORTAL_RESP2%%|*}" "201" "POST статьи портала владельцем"
PORTAL_ART_ID=$(json_id "${PORTAL_RESP2#*|}")
require_id "$PORTAL_ART_ID" "articleId статьи портала"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles" "POST" \
  "{\"question\":\"$KB Чужая статья?\",\"answer\":\"Не должно создаться.\",\"categoryId\":$CAT_ID,\"portalId\":$PORTAL_ID}")" \
  "403" "POST статьи в чужой портал посторонним"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles?portalId=$PORTAL_ID")" "403" "поиск по чужому приватному порталу"
check "$(page_has_id "$(kb_get "$USER_TOKEN" "/knowledgebase/articles?portalId=$PORTAL_ID")" "$PORTAL_ART_ID")" "yes" "поиск по своему порталу"

check "$(page_has_id "$(kb_get "$OPER_TOKEN" "/knowledgebase/articles")" "$PORTAL_ART_ID")" "no" "общая выдача без статей чужих приватных порталов"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles/$PORTAL_ART_ID")" "404" "GET статьи чужого приватного портала по id (не раскрывает существование)"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles/$PORTAL_ART_ID" "DELETE")" "403" "DELETE статьи чужого портала"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$PORTAL_ART_ID" "PUT" \
  "{\"question\":\"$KB Как вернуть деньги?\",\"answer\":\"Через поддержку.\",\"categoryId\":$CAT_ID,\"portalId\":null}")" \
  "403" "PUT перевода статьи портала в глобальные пользователем"

# ── Доверенный пользователь портала ───────────────────────────

step "Доверенный пользователь портала: статьи портала"
# Владелец — оператор, доверенный — обычный пользователь: только у пользователя нет
# ROLE_OPERATOR, поэтому проверка «доверенный не создаёт глобальную статью» осмысленна.
TRUSTED_PORTAL_RESP=$(kb_post "$OPER_TOKEN" "/portals" \
  "{\"name\":\"$KB-portal-trusted\",\"description\":\"портал доверенного\"}")
check "${TRUSTED_PORTAL_RESP%%|*}" "201" "POST /portals оператором"
TRUSTED_PORTAL_ID=$(json_id "${TRUSTED_PORTAL_RESP#*|}")
require_id "$TRUSTED_PORTAL_ID" "portalId портала доверенного"

USER_ID=$($PSQL "select id from users where email='$USER_EMAIL';" | tail -1)
require_id "$USER_ID" "id пользователя"
check "$(kb_code "$OPER_TOKEN" "/portals/$TRUSTED_PORTAL_ID/settings/users" "PUT" "{\"userIds\":[$USER_ID]}")" \
  "200" "PUT участников портала владельцем"

TRUSTED_ART=$(kb_post "$USER_TOKEN" "/knowledgebase/articles" \
  "{\"question\":\"$KB Доверенный: как сменить тариф?\",\"answer\":\"В настройках портала.\",\"categoryId\":$CAT_ID,\"portalId\":$TRUSTED_PORTAL_ID}")
check "${TRUSTED_ART%%|*}" "201" "POST статьи портала доверенным пользователем"
TRUSTED_ART_ID=$(json_id "${TRUSTED_ART#*|}")
require_id "$TRUSTED_ART_ID" "articleId статьи доверенного"

TRUSTED_ETAG=$(kb_etag "$USER_TOKEN" "/knowledgebase/articles/$TRUSTED_ART_ID")
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$TRUSTED_ART_ID" "PATCH" \
  '{"answer":"В настройках портала, раздел «Тариф»."}' "$TRUSTED_ETAG")" "200" \
  "PATCH статьи портала доверенным пользователем"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$TRUSTED_ART_ID" "DELETE")" "204" \
  "DELETE статьи портала доверенным пользователем"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$TRUSTED_ART_ID")" "404" \
  "GET удалённой доверенным статьи"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles" "POST" \
  "{\"question\":\"$KB Доверенный: глобальная?\",\"answer\":\"Не должно создаться.\",\"categoryId\":$CAT_ID,\"portalId\":null}")" \
  "403" "POST глобальной статьи доверенным пользователем"

# Снимаем участника: права доверенного отзываются сразу, без перевыпуска токена.
check "$(kb_code "$OPER_TOKEN" "/portals/$TRUSTED_PORTAL_ID/settings/users" "PUT" '{"userIds":[]}')" "200" \
  "PUT пустого списка участников портала"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles" "POST" \
  "{\"question\":\"$KB Отозванный доступ?\",\"answer\":\"Не должно создаться.\",\"categoryId\":$CAT_ID,\"portalId\":$TRUSTED_PORTAL_ID}")" \
  "403" "POST статьи портала после отзыва доступа"

# ── Удаление ──────────────────────────────────────────────────

step "Удаление статьи и категории"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID" "DELETE")" "409" "DELETE категории с активными статьями"

check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$PORTAL_ART_ID" "DELETE")" "204" "DELETE статьи портала владельцем"
check "$(kb_code "$USER_TOKEN" "/knowledgebase/articles/$PORTAL_ART_ID")" "404" "GET удалённой статьи"
check "$(page_has_id "$(kb_get "$USER_TOKEN" "/knowledgebase/articles?portalId=$PORTAL_ID")" "$PORTAL_ART_ID")" "no" "выдача без удалённой статьи"

# Мягко удалённая строка всё ещё ссылается на категорию по FK — категория остаётся занятой.
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID" "DELETE")" "409" "DELETE категории с мягко удалённой статьёй"

check "$(kb_code "$OPER_TOKEN" "/knowledgebase/articles/$ART_ID" "DELETE")" "204" "DELETE глобальной статьи оператором"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$CAT_ID" "DELETE")" "409" "DELETE категории, пока в ней остались статьи"

# Пустая категория удаляется: ограничение — только по фактическим статьям, не по всей истории.
EMPTY_CAT=$(kb_post "$OPER_TOKEN" "/knowledgebase/categories" "{\"name\":\"$KB Пустая\"}")
check "${EMPTY_CAT%%|*}" "201" "POST пустой категории"
EMPTY_CAT_ID=$(json_id "${EMPTY_CAT#*|}")
require_id "$EMPTY_CAT_ID" "categoryId пустой категории"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$EMPTY_CAT_ID" "DELETE")" "204" "DELETE пустой категории"
check "$(kb_code "$OPER_TOKEN" "/knowledgebase/categories/$EMPTY_CAT_ID")" "404" "GET удалённой категории"

echo
if [ "$COUNT_FAIL" -eq 0 ]; then
  echo "═══ ИТОГ: все проверки прошли ($COUNT_PASS) ═══"
else
  echo "═══ ИТОГ: провалено $COUNT_FAIL из $((COUNT_PASS + COUNT_FAIL)) ═══"
fi
exit "$COUNT_FAIL"

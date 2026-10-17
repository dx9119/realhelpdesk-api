#!/usr/bin/env bash
#
# Переключение профиля приложения в infrastructure/.env с пересозданием контейнера app.
#
#   bash scripts/docker/set-profile.sh dev            # внутренний JWT-контур
#   bash scripts/docker/set-profile.sh dev,keycloak   # токены выпускает Keycloak
#
# Профиль читает spring.profiles.active в application.properties, значение
# приходит из SPRING_PROFILES_ACTIVE (infrastructure/.env — единственное место,
# где он задан). env_file контейнера перекрывает переменные окружения хоста,
# поэтому переключение возможно только правкой файла.
#
# Ключ keycloak — второй, а не самостоятельный: он добавляет свои ключи
# (application-keycloak.properties) к базовому dev/prod. Контур Keycloak
# обслуживает сервис keycloak профиля dev, поэтому профиль без keycloak
# работает и без него — им пользуются e2e-сценарии и нагрузочный прогон
# (они ходят в /api/v1/auth/* по cookie).
set -euo pipefail

cd "$(dirname "$0")/../.."

ENV_FILE="infrastructure/.env"
COMPOSE="docker compose -f infrastructure/docker-compose.yaml --profile infra --profile dev --profile app"
HEALTH_URL="http://localhost:8081/actuator/health"

PROFILES=${1:-}
if [ -z "$PROFILES" ]; then
    echo "Укажите профили: $0 dev|dev,keycloak|prod|prod,keycloak" >&2
    exit 1
fi

case "$PROFILES" in
    *[!a-z0-9,]*|,*) echo "Недопустимые профили: $PROFILES (ожидается список через запятую)" >&2; exit 1 ;;
esac

if [ ! -f "$ENV_FILE" ]; then
    echo "Нет $ENV_FILE — выполните 'make env'" >&2
    exit 1
fi

current=$(grep '^SPRING_PROFILES_ACTIVE=' "$ENV_FILE" | tail -1 | cut -d= -f2- || true)
if [ "$current" = "$PROFILES" ]; then
    # Пересоздание не нужно: контейнер уже поднят с этим профилем (обычный случай
    # для make e2e-* и make load — их зависимость просто гарантирует контур).
    echo "$ENV_FILE: SPRING_PROFILES_ACTIVE уже $PROFILES"
    exit 0
fi

if grep -q '^SPRING_PROFILES_ACTIVE=' "$ENV_FILE"; then
    sed -i "s|^SPRING_PROFILES_ACTIVE=.*|SPRING_PROFILES_ACTIVE=$PROFILES|" "$ENV_FILE"
else
    printf 'SPRING_PROFILES_ACTIVE=%s\n' "$PROFILES" >> "$ENV_FILE"
fi
echo "$ENV_FILE: SPRING_PROFILES_ACTIVE=$current -> $PROFILES"

if ! $COMPOSE ps app --status running -q | grep -q .; then
    echo "app не запущен — профиль применён, старт: make up"
    exit 0
fi

# NO_RESTART=1 — только записать профиль: контейнер пересоздаст следующая
# команда (make up-load делает это сама вместе со сборкой образа).
if [ "${NO_RESTART:-0}" = "1" ]; then
    echo "NO_RESTART=1 — пересоздание app пропущено"
    exit 0
fi

# Старый контейнер продолжает отвечать до полного пересоздания: ждём смены id,
# иначе health=200 придёт от прежнего процесса со старым профилем.
old_id=$($COMPOSE ps -q app)
$COMPOSE up -d --force-recreate --no-deps app >/dev/null
for _ in $(seq 1 60); do
    [ "$($COMPOSE ps -q app)" = "$old_id" ] || break
    sleep 1
done

echo -n "Ожидание готовности app (профиль $PROFILES)"
for _ in $(seq 1 90); do
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
        echo " — готов"
        exit 0
    fi
    echo -n "."
    sleep 2
done

echo
echo "app не ответил на $HEALTH_URL за 180 с — смотрите: make logs" >&2
exit 1

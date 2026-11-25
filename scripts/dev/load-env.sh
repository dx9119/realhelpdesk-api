#!/usr/bin/env bash
# Загрузка infrastructure/.env и дефолтов compose-интерполяции для запуска
# приложения НА ХОСТЕ (make run-local, e2e-сценарии, поднимающие app сами).
#
# В контейнере это делает compose (env_file + ${VAR:-default}); на хосте тех же
# значений должен дать shell, иначе схема ${VAR} в application.properties не
# резолвится, а MINIO_ENDPOINT из .env (http://minio:9000) перебьёт
# application-host.properties — OS-env приоритетнее профильных файлов.
#
# Использование: source scripts/dev/load-env.sh   (после cd в корень репозитория)

ENV_FILE="${ENV_FILE:-infrastructure/.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Нет $ENV_FILE — выполните: make env" >&2
  return 1 2>/dev/null || exit 1
fi

# infrastructure/.env — не shell-скрипт: значения с пробелами (APP_NAME=Real
# help desk, STATIC_CACHE_CONTROL=private, no-store) ломают shell-источникование,
# поэтому файл загружается построчно как KEY=VALUE — так же, как читают его
# docker (env_file) и IDEA (EnvFile).
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    '' | '#'*) continue ;;
    [A-Za-z_]*=*)
      key=${line%%=*}
      value=${line#*=}
      # ${VAR:-default} из .env (compose-синтаксис, см. .env.dev.example) shell не
      # раскрывает: приложение получило бы литерал и упало на circular placeholder.
      # outer-переменная на хосте не задаётся, поэтому берём default — как compose.
      if [[ $value == *'${'*':-'*'}' ]]; then
        value=${value#*:-}
        value=${value%\}}
      fi
      export "$key=$value"
      ;;
  esac
done < "$ENV_FILE"

# Дефолты compose-интерполяции (:-) из infrastructure/docker-compose.yaml.
export OTLP_TRACING_ENDPOINT="${OTLP_TRACING_ENDPOINT:-http://localhost:4318/v1/traces}"

# .env может указывать Collector контейнерным именем (otlp в docker-сети),
# с хоста он доступен на loopback — порт 4318 проброшен в docker-compose.obs.yaml.
case "${OTLP_TRACING_ENDPOINT:-}" in
  *://otel-collector:*)
    export OTLP_TRACING_ENDPOINT="http://localhost:4318/v1/traces"
    ;;
esac
export TRUST_PROXY_HEADERS="${TRUST_PROXY_HEADERS:-false}"
export CLIENT_IP_FORWARDED_INDEX="${CLIENT_IP_FORWARDED_INDEX:-0}"

# Адреса инфраструктуры зацикливаются на хост: контейнерные имена (minio) из
# .env приложениям, запущенным вне сети compose, недоступны.
export MINIO_ENDPOINT="http://127.0.0.1:${MINIO_API_PORT:-9000}"

# То же для БД: .env адресует postgres контейнерным именем, а OS-env приоритетнее
# application-host.properties — без подмены host-профиль не получит loopback.
case "${DB_URL:-}" in
  *//postgres:*)
    export DB_URL="${DB_URL//\/\/postgres:/\/\/127.0.0.1:}"
    ;;
esac

# Keycloak с хоста: KC_JWK_SET_URI из .env указывает на контейнерное имя
# keycloak:8080, а OS-env приоритетнее application-host.properties — переопределить
# его в профильном файле нельзя, поэтому loopback задаётся здесь.
case "${KC_JWK_SET_URI:-}" in
  *://keycloak:* | '')
    export KC_JWK_SET_URI="http://127.0.0.1:${KEYCLOAK_PORT:-8180}/realms/realhelpdesk/protocol/openid-connect/certs"
    ;;
esac

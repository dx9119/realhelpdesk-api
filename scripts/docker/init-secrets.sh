#!/usr/bin/env bash
#
# Инициализация локальных секретов infrastructure/.env (make env).
#
# Что делает:
#   1. если infrastructure/.env нет — копирует шаблон infrastructure/.env.example;
#   2. заполняет ПУСТЫЕ обязательные секреты случайными значениями через openssl
#      (JWT_SECRET, CAPTCHA_HMAC_SECRET, DB_PASSWORD, MINIO_ROOT_PASSWORD,
#      METRICS_PASSWORD, KEYCLOAK_DB_PASSWORD, MINIO_ROOT_USER=minioadmin) — локальный стенд
#      поднимается без ручного заполнения;
#   3. если .env уже существует — ничего в нём не меняет, только предупреждает
#      о ключах, которых нет в шаблоне (добавили переменную в .env.example —
#      допишите её сюда сами).
#
# Почему так просто: значения несекретных ключей живут в одном месте —
# infrastructure/.env.example (и в compose-дефолтах ${VAR:-...} для динамических
# ключей), а не размазаны между шаблоном, снимком дефолтов и .env. Поэтому
# синхронизировать .env с шаблоном больше не нужно: скрипт только создаёт файл
# и генерирует секреты.
#
# ВАЖНО про существующие секреты: значение секрета НЕ перегенерируется, даже
# если оно изменилось в шаблоне. Смена DB_PASSWORD у уже поднятого стека
# рассогласует приложение и volume PostgreSQL (пароль задаётся при инициализации
# тома), поэтому секреты меняют осознанно: make down-volumes && make up —
# либо правкой пароля в самой БД.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT_DIR/infrastructure/.env.example"
ENV_FILE="$ROOT_DIR/infrastructure/.env"

log() { printf '%s\n' "$*"; }
warn() { printf 'ПРЕДУПРЕЖДЕНИЕ: %s\n' "$*" >&2; }

if [[ ! -f "$EXAMPLE" ]]; then
    echo "Не найден шаблон: $EXAMPLE" >&2
    exit 1
fi

mkdir -p "$(dirname "$ENV_FILE")"

created=0
if [[ ! -f "$ENV_FILE" ]]; then
    cp "$EXAMPLE" "$ENV_FILE"
    created=1
    log "Создан infrastructure/.env из шаблона — секреты будут сгенерированы"
fi

# --- Автогенерация локальных секретов ---
# Пустые обязательные секреты заполняются одноразово: локальный dev-стенд
# поднимается без ручного заполнения. Значения — случайные, только для этой
# машины; на сервере/в проде секреты задаются вручную. Уже заполненные
# значения не трогаются (grep ищет ровно пустую строку).
gen_secret() { # gen_secret KEY [LENGTH_HEX]
    local key="$1" len="${2:-32}" val
    val=$(openssl rand -hex "$len")
    # sed -i с | как разделителем: hex не содержит |, безопасно
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
    log "Секрет сгенерирован: $key"
}

if grep -q '^MINIO_ROOT_USER=$' "$ENV_FILE"; then
    sed -i 's|^MINIO_ROOT_USER=$|MINIO_ROOT_USER=minioadmin|' "$ENV_FILE"
    log "Сгенерировано: MINIO_ROOT_USER=minioadmin"
fi

generated=0
for key in JWT_SECRET CAPTCHA_HMAC_SECRET DB_PASSWORD MINIO_ROOT_PASSWORD METRICS_PASSWORD KEYCLOAK_DB_PASSWORD; do
    if grep -q "^${key}=$" "$ENV_FILE"; then
        gen_secret "$key"
        generated=$((generated + 1))
    fi
done

# --- Предупреждение о расхождении ключей (без записи) ---
# Ключи, объявленные в шаблоне, но отсутствующие в .env: приложение упадёт на
# старте с "Could not resolve placeholder". Дописывать их автоматически не
# нужно — ключ может быть намеренно удалён из личного окружения.
missing=()
while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)= ]] || continue
    key="${BASH_REMATCH[1]}"
    grep -q "^${key}=" "$ENV_FILE" || missing+=("$key")
done < "$EXAMPLE"

if ((created)); then
    log "Готово. Локальные секреты сгенерированы — можно запускать стек (make up)."
elif ((generated > 0)); then
    log "infrastructure/.env: сгенерировано пустых секретов — $generated."
else
    log "infrastructure/.env уже готов (секреты на месте) — ничего не изменено."
fi

if ((${#missing[@]} > 0)); then
    warn "в infrastructure/.env нет ключей из шаблона: ${missing[*]}"
    warn "допишите их вручную, если они нужны текущему контуру (шаблон — $EXAMPLE)"
fi

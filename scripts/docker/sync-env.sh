#!/usr/bin/env bash
#
# Синхронизация infrastructure/.env с шаблоном infrastructure/.env.example.
#
# Что делает:
#   1. создаёт .env из шаблона, если файла нет;
#   2. дописывает в .env ключи, которых нет (с дефолтным значением из шаблона);
#   3. обновляет значения ранее дефолтных ключей: если в .env всё ещё стоит
#      прежний дефолт (сверка со снимком infrastructure/.env.defaults), а в
#      шаблоне появилось новое значение — строка заменяется;
#   4. пользовательские значения (в т.ч. секреты) не трогает, только
#      предупреждает, если дефолт в шаблоне изменился;
#   5. обновляет снимок дефолтов .env.defaults;
#   6. заполняет пустые обязательные секреты (JWT_SECRET, CAPTCHA_HMAC_SECRET,
#      DB_PASSWORD, MINIO_ROOT_PASSWORD, METRICS_PASSWORD) случайными
#      значениями через openssl rand — локальный стенд поднимается без
#      ручного заполнения. На сервере/в проде секреты задаются вручную.
#
# Так можно быстро менять конфиги/лимиты в .env.example и одной командой
# безопасно применять их, не затирая реальные секреты.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT_DIR/infrastructure/.env.example"
ENV_FILE="$ROOT_DIR/infrastructure/.env"
SNAPSHOT="$ROOT_DIR/infrastructure/.env.defaults"

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
    log "Создан infrastructure/.env из шаблона — заполните секреты"
fi

declare -A example_values=()
declare -A snapshot_values=()
example_keys=()

while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    example_keys+=("$key")
    example_values["$key"]="$value"
done < "$EXAMPLE"

snapshot_exists=0
if [[ -f "$SNAPSHOT" ]]; then
    snapshot_exists=1
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
        snapshot_values["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
    done < "$SNAPSHOT"
fi

tmp="$(mktemp "${ENV_FILE}.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

declare -A seen=()
updated=0

while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*# ]] || [[ "$line" =~ ^[[:space:]]*$ ]] || [[ ! "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)= ]]; then
        printf '%s\n' "$line" >> "$tmp"
        continue
    fi

    key="${BASH_REMATCH[1]}"
    seen["$key"]=1

    if [[ -z "${example_values["$key"]+x}" ]]; then
        printf '%s\n' "$line" >> "$tmp"
        continue
    fi

    current_value="${line#*=}"
    new_value="${example_values[$key]}"

    if [[ "$current_value" == "$new_value" ]]; then
        printf '%s\n' "$line" >> "$tmp"
        continue
    fi

    if ((snapshot_exists)) && [[ -n "${snapshot_values["$key"]+x}" ]] && [[ "$current_value" == "${snapshot_values[$key]}" ]]; then
        printf '%s=%s\n' "$key" "$new_value" >> "$tmp"
        log "Обновлено: $key=$new_value"
        updated=$((updated + 1))
    elif [[ -n "$new_value" ]]; then
        warn "$key: в .env своё значение, в шаблоне другое — оставляю как есть ($key=${current_value:-<не задано>}, шаблон=$new_value)"
        printf '%s\n' "$line" >> "$tmp"
    else
        printf '%s\n' "$line" >> "$tmp"
    fi
done < "$ENV_FILE"

missing=()
for key in "${example_keys[@]}"; do
    if [[ -z "${seen["$key"]+x}" ]]; then
        missing+=("$key")
    fi
done

if ((${#missing[@]} > 0)); then
    printf '\n# --- добавлено sync-env из шаблона ---\n' >> "$tmp"
    for key in "${missing[@]}"; do
        printf '%s=%s\n' "$key" "${example_values[$key]}" >> "$tmp"
        log "Добавлено: $key=${example_values[$key]}"
    done
fi

chmod --reference="$ENV_FILE" "$tmp" 2>/dev/null || true
mv "$tmp" "$ENV_FILE"
trap - EXIT

: > "$SNAPSHOT"
for key in "${example_keys[@]}"; do
  printf '%s=%s\n' "$key" "${example_values[$key]}" >> "$SNAPSHOT"
done

# --- Автогенерация локальных секретов ---
# Пустые обязательные секреты заполняются одноразово: локальный dev-стенд
# поднимается без ручного заполнения. Значения — случайные, только для этой
# машины; на сервере/в проде секреты задаются вручную (или через CI), сюда
# их переносить не нужно. MINIO_ROOT_USER — фиксированное имя (не секрет).
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

for key in JWT_SECRET CAPTCHA_HMAC_SECRET DB_PASSWORD MINIO_ROOT_PASSWORD METRICS_PASSWORD; do
  if grep -q "^${key}=$" "$ENV_FILE"; then
    gen_secret "$key"
  fi
done

if ((created)); then
  log "Готово. Локальные секреты сгенерированы — можно запускать стек (make up)."
elif ((updated > 0 || ${#missing[@]} > 0)); then
  log "infrastructure/.env синхронизирован с шаблоном."
else
  log "infrastructure/.env уже соответствует шаблону."
fi

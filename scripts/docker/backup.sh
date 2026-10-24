#!/usr/bin/env bash
# Бэкап рабочего контура: PostgreSQL (pg_dump -Fc) + MinIO (mc mirror).
# Запуск: make prod-backup  (или bash scripts/docker/backup.sh)
# Восстановление: make prod-restore BACKUP_FILE=backups/db_....dump
#
# Артефакты складываются в ./backups/ (в корне проекта):
#   db_YYYY-MM-DD_HH-MM.dump  — custom-формат pg_dump (сжат, pg_restore)
#   minio_YYYY-MM-DD_HH-MM/   — зеркало бакета MinIO
# Ротация: артефакты старше BACKUP_RETENTION_DAYS (14) удаляются.

set -euo pipefail

cd "$(dirname "$0")/../.."

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"

# Имя БД/пользователя — из infrastructure/.env (пароль уже в окружении compose)
set -a
# shellcheck disable=SC1091
. infrastructure/.env
set +a

DB_USER="${POSTGRES_USER:-user}"
DB_NAME="${POSTGRES_DB:-desk}"
TS="$(date +%F_%H-%M)"

mkdir -p "$BACKUP_DIR"

echo "==> Бэкап PostgreSQL ($DB_NAME) → $BACKUP_DIR/db_${TS}.dump"
docker compose -f infrastructure/docker-compose.yaml --profile infra exec -T postgres \
  pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$BACKUP_DIR/db_${TS}.dump"

echo "==> Бэкап MinIO (бакет ${MINIO_BUCKET:-realhelpdesk-attachments}) → $BACKUP_DIR/minio_${TS}/"
mkdir -p "$BACKUP_DIR/minio_${TS}"
# Отдельный minio/mc-контейнер в сети infra: зеркалит бакет на хост.
# Образ minio/mc не зависит от версии объектного хранилища (silo/minio).
docker run --rm --network realhelpdesk_data \
  -v "$(pwd)/$BACKUP_DIR/minio_${TS}:/backup" \
  -e "MC_HOST_local=http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000" \
  minio/mc:RELEASE.2025-09-07T16-13-09Z \
  mirror --overwrite "local/${MINIO_BUCKET:-realhelpdesk-attachments}" /backup/

echo "==> Ротация: удаление артефактов старше ${BACKUP_RETENTION_DAYS} дней"
find "$BACKUP_DIR" -mindepth 1 -mtime "+$BACKUP_RETENTION_DAYS" -delete

echo "==> Готово. Содержимое $BACKUP_DIR:"
ls -lh "$BACKUP_DIR" | tail -n +2

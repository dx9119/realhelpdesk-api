-- Локальный JWT-контур удалён: вход и выпуск токенов — за Keycloak.
-- Таблица падает вместе с FK, CHECK, уникальным индексом token_refresh и
-- индексом idx_jwt_tokens_created_at (changeset 004).
DROP TABLE IF EXISTS jwt_tokens;

ALTER TABLE users DROP COLUMN IF EXISTS password_hash;
ALTER TABLE users DROP COLUMN IF EXISTS token_version;
ALTER TABLE users DROP COLUMN IF EXISTS recovery_passwd_token;

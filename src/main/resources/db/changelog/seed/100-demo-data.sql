-- Локальные фикстуры (context="seed"): демо-пользователи, портал, заявка с диалогом.
-- В контуре по умолчанию не применяются: приложение работает с
-- DB_MIGRATIONS_CONTEXTS=schema и оставляет базу пустой. Сидировать —
-- make dev-db-seed (или DB_MIGRATIONS_CONTEXTS=schema,seed + перезапуск app).
--
-- Пароли совпадают с realm'ом Keycloak (infrastructure/keycloak/
-- realhelpdesk-realm.json): user-pass / operator-pass. Адреса при этом
-- отличаются от realm-пользователей (user@ / operator@): Keycloak создаёт
-- своих при первом входе, seed с ними не конфликтует.
--
-- Все вставки идемпотентны (ON CONFLICT / NOT EXISTS) — повторный прогон
-- безопасен. Чистая схема вместо сида: make dev-down-volumes && make dev-up.

-- Пользователь: пароль user-pass (BCrypt)
INSERT INTO users (first_name, last_name, email, is_email_verified, password_hash,
                   user_role, user_status, user_platform_source, token_version,
                   created_at, portal_cunt_limit, portal_shared_users_count_limit, version)
VALUES ('Иван', 'Пользователь', 'demo-user@example.com', true,
        '$2a$10$jyedYI3CI3UdvGy6tS9PvODbjUgyS7VU5ZHjqv1CLEEWJ5YK9e8FG',
        'ROLE_USER', 'ACTIVE', 'LOCAL', 0, now(), 10, 10, 0)
ON CONFLICT (email) DO NOTHING;

-- Оператор: пароль operator-pass (BCrypt)
INSERT INTO users (first_name, last_name, email, is_email_verified, password_hash,
                   user_role, user_status, user_platform_source, token_version,
                   created_at, portal_cunt_limit, portal_shared_users_count_limit, version)
VALUES ('Ольга', 'Оператор', 'demo-operator@example.com', true,
        '$2a$10$zt/bpJe8CS4e7.iHieQl1eHN16ei6qkQmjQ.5c6uf7tHphMgE3FEm',
        'ROLE_OPERATOR', 'ACTIVE', 'LOCAL', 0, now(), 10, 10, 0)
ON CONFLICT (email) DO NOTHING;

-- Портал пользователя
INSERT INTO portals (name, description, owner_id, is_deleted, is_public, version, created_at)
SELECT 'Демо-портал', 'Портал из seed-фикстур: демо-заявки и переписка', u.id, false, false, 0, now()
FROM users u
WHERE u.email = 'demo-user@example.com'
ON CONFLICT (owner_id, name) DO NOTHING;

-- Заявка в демо-портале
INSERT INTO ticket_model (title, body, author_id, portal_id, ticket_priority, ticket_status,
                          ticket_live_status, version, access_status, created_at)
SELECT 'Демо-заявка: не отправляются письма',
       'Письма о новых заявках не приходят получателям. Воспроизводится на демо-портале.',
       u.id, p.id, 'MEDIUM', 'OPEN', 'ACTIVE', 0, 1, now()
FROM users u
JOIN portals p ON p.owner_id = u.id AND p.name = 'Демо-портал'
WHERE u.email = 'demo-user@example.com'
  AND NOT EXISTS (SELECT 1 FROM ticket_model t WHERE t.title = 'Демо-заявка: не отправляются письма');

-- Сообщение автора в заявке
INSERT INTO message_model (version, ticket_id, author_id, message_text, created_at)
SELECT 0, t.id, u.id, 'Заявка заведена из seed-фикстур, можно тестировать диалог.', now()
FROM ticket_model t
JOIN users u ON u.id = t.author_id AND u.email = 'demo-user@example.com'
WHERE t.title = 'Демо-заявка: не отправляются письма'
  AND NOT EXISTS (SELECT 1 FROM message_model m WHERE m.ticket_id = t.id AND m.author_id = u.id);

-- Ответ оператора
INSERT INTO message_model (version, ticket_id, author_id, message_text, created_at)
SELECT 0, t.id, o.id, 'Добрый день! Посмотрели заявку, готовим фикс. (seed-ответ оператора)', now()
FROM ticket_model t
JOIN users o ON o.email = 'demo-operator@example.com'
WHERE t.title = 'Демо-заявка: не отправляются письма'
  AND NOT EXISTS (SELECT 1 FROM message_model m WHERE m.ticket_id = t.id AND m.author_id = o.id);

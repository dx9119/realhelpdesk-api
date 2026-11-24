-- Мягкое удаление категорий базы знаний.
-- Обязательно должен совпадать с KnowledgeBaseCategoryModel:
--     id, name, description, created_at, is_deleted, time_delete, version
--
-- Флаг видимости как у порталов и статей: DELETE скрывает категорию, строка
-- остаётся в БД, потому что на неё по FK ссылаются статьи (в том числе мягко
-- удалённые) — физический DELETE падал бы нарушением целостности.
-- Физическое удаление — отдельная операторская операция.

ALTER TABLE knowledgebase_categories
    ADD COLUMN is_deleted  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN time_delete TIMESTAMP(6) WITH TIME ZONE;

-- Уникальность имени нужна только среди видимых категорий: удалённая «Оплата»
-- не должна мешать создать новую с тем же названием. Частичный индекс
-- заменяет прежний полный knowledgebase_categories_name_lower_key.
DROP INDEX knowledgebase_categories_name_lower_key;

CREATE UNIQUE INDEX knowledgebase_categories_name_lower_active_key
    ON knowledgebase_categories (lower(name))
    WHERE is_deleted = false;

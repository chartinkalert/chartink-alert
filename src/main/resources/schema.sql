-- 1) Create table if new DB
CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL,
                                        updated_at BIGINT
);

-- 2) Add the new column if upgrading old DB
ALTER TABLE user_map
    ADD COLUMN IF NOT EXISTS user_key TEXT;

-- 3) Backfill missing keys for existing rows (important!)
-- This fills user_key for old users so app won't break.
UPDATE user_map
SET user_key = md5(random()::text || clock_timestamp()::text)
WHERE user_key IS NULL OR user_key = '';

-- 4) Enforce 1 uid per telegram account
ALTER TABLE user_map
    ADD CONSTRAINT IF NOT EXISTS ux_user_map_chat_id UNIQUE (chat_id);

-- 5) Make sure user_key is required + unique
ALTER TABLE user_map
    ALTER COLUMN user_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_user_key ON user_map(user_key);
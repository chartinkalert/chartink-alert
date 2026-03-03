-- 1) Create table if it doesn't exist
CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL,
                                        updated_at BIGINT
);

-- 2) Add new column (migration-safe)
ALTER TABLE user_map
    ADD COLUMN IF NOT EXISTS user_key TEXT;

-- 3) Backfill user_key for old rows
UPDATE user_map
SET user_key = md5(random()::text || clock_timestamp()::text)
WHERE user_key IS NULL OR user_key = '';

-- 4) Enforce 1 UID per Telegram account (use unique index, not constraint)
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_chat_id ON user_map(chat_id);

-- 5) Ensure user_key is required + unique
ALTER TABLE user_map
    ALTER COLUMN user_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_user_key ON user_map(user_key);
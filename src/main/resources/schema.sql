CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL,
                                        updated_at BIGINT
);

-- one uid per telegram account
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_chat_id
    ON user_map(chat_id);
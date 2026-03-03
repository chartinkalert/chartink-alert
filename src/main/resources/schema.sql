CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL,
                                        updated_at INTEGER
);

DELETE FROM user_map;

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_chat_id
    ON user_map(chat_id);
CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL UNIQUE,
                                        user_key TEXT NOT NULL,
                                        updated_at BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_user_key ON user_map(user_key);
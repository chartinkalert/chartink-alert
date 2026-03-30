-- 1) Create main user table
CREATE TABLE IF NOT EXISTS user_map (
                                        uid TEXT PRIMARY KEY,
                                        chat_id TEXT NOT NULL,
                                        user_key TEXT NOT NULL,
                                        updated_at BIGINT
);

-- 2) Create unique indexes
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_chat_id ON user_map(chat_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_map_user_key ON user_map(user_key);

-- 3) Create usage tables
CREATE TABLE IF NOT EXISTS daily_usage (
                                           day DATE NOT NULL,
                                           chat_id TEXT NOT NULL,
                                           alerts_count INT NOT NULL DEFAULT 0,
                                           PRIMARY KEY (day, chat_id)
    );

-- 4) Create telegram update tracker
CREATE TABLE IF NOT EXISTS telegram_updates (
                                                update_id BIGINT PRIMARY KEY,
                                                processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
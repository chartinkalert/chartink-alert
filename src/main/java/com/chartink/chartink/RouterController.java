package com.chartink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;
import java.util.List;

@RestController
public class RouterController {

    private final JdbcTemplate jdbc;

    // Dedup Telegram retries by update_id
    private final Set<Long> seenUpdates = ConcurrentHashMap.newKeySet();

    // Generators
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] UID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no I,O,0,1
    private static final char[] KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    @Value("${telegram.botToken}")
    private String botToken;

    // Public URL of your server (Railway domain)
    // Add in application.yaml:
    // app:
    //   publicUrl: ${APP_PUBLIC_URL}
    @Value("${app.publicUrl}")
    private String publicUrl;

    @Value("${admin.chatId:}")
    private String adminChatId;

    public RouterController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 1) Chartink will call this
    // Now validates: uid + per-user key stored in DB
    @PostMapping(
            value = "/chartink",
            consumes = MediaType.ALL_VALUE,
            produces = "text/plain; charset=UTF-8"
    )
    public String chartinkWebhook(
            @RequestParam("uid") String uid,
            @RequestParam("key") String key,
            @RequestBody(required = false) String body
    ) throws Exception {

        if (!StringUtils.hasText(uid)) return "NO_UID";
        if (!StringUtils.hasText(key)) return "NO_KEY";

        String normalizedUid = uid.trim().toLowerCase();
        String providedKey = key.trim();

        // Fetch chat_id + user_key for uid
        Map<String, Object> row = jdbc.query(
                "SELECT chat_id, user_key FROM user_map WHERE uid = ?",
                rs -> rs.next()
                        ? Map.of("chat_id", rs.getString("chat_id"), "user_key", rs.getString("user_key"))
                        : null,
                normalizedUid
        );

        if (row == null) return "UID_NOT_LINKED";

        String expectedKey = (String) row.get("user_key");
        if (expectedKey == null || !expectedKey.equals(providedKey)) return "FORBIDDEN";

        String chatId = (String) row.get("chat_id");
        int currentUsage = getTodayUsageFromDailyTable(chatId);
        if (currentUsage >= 50) {
            return "LIMIT_EXCEEDED";
        }
        incrementTodayUsage(chatId);

        String msg = buildMessage(normalizedUid, body);
        sendTelegram(chatId, msg);

        return "OK";
    }
    private int getTodayUsageFromDailyTable(String chatId) {
        // Using a list query to avoid EmptyResultDataAccessException if no row exists
        List<Integer> counts = jdbc.query(
                "SELECT alerts_count FROM daily_usage WHERE chat_id = ? AND day = ?",
                (rs, rowNum) -> rs.getInt("alerts_count"),
                chatId, java.sql.Date.valueOf(LocalDate.now())
        );

        // If the list is empty, it means no alerts have been sent today yet
        return counts.isEmpty() ? 0 : counts.get(0);
    }
    // 2) Telegram webhook
    @PostMapping(value = "/telegram", produces = "text/plain; charset=UTF-8")
    public String telegramWebhook(@RequestBody Map<String, Object> update) {

        // Dedup retries
        Object updateIdObj = update.get("update_id");
        if (updateIdObj instanceof Number) {
            long updateId = ((Number) updateIdObj).longValue();
            if (!seenUpdates.add(updateId)) return "OK";
            if (seenUpdates.size() > 5000) seenUpdates.clear();
        }

        try {
            Object messageObj = update.get("message");
            if (messageObj == null) messageObj = update.get("edited_message");
            if (messageObj == null) messageObj = update.get("channel_post");
            if (messageObj == null) messageObj = update.get("edited_channel_post");
            if (!(messageObj instanceof Map)) return "OK";

            Map<?, ?> message = (Map<?, ?>) messageObj;

            Object chatObj = message.get("chat");
            Object textObj = message.get("text");

            if (!(chatObj instanceof Map) || !(textObj instanceof String)) return "OK";

            Map<?, ?> chat = (Map<?, ?>) chatObj;
            Object chatIdObj = chat.get("id");
            if (chatIdObj == null) return "OK";

            String chatId = String.valueOf(chatIdObj);
            String text = ((String) textObj).trim();

            // Commands
            if (text.startsWith("/start")) {
                handleStart(chatId);
                return "OK";
            }
            if (text.startsWith("/myuid")) {
                handleMyUid(chatId);
                return "OK";
            }
            if (text.startsWith("/unlink")) {
                handleUnlink(chatId, text);
                return "OK";
            }
            if (text.startsWith("/newuid")) {
                handleNewUid(chatId, text);
                return "OK";
            }
            if (text.startsWith("/more")) {
                sendTelegram(chatId, "⚙️ *Other Actions*\n\n" +
                        "⚠️ *Warning:* These actions will make your current Chartink webhook URL stop working.\n\n" +
                        "/newuid - Generate a brand new URL\n" +
                        "/unlink - Delete your account link");
                return "OK";
            }
            // Inside the telegramWebhook method's command block
            if (text.startsWith("/stats")) {
                int today = getTodayUsageFromDailyTable(chatId);
                int limit = 50;
                sendTelegram(chatId, "📊 *Daily Usage*\n" +
                        "Used: " + today + " / " + limit + "\n" +
                        "Remaining: " + (limit - today));
                return "OK";
            }
            if (text.startsWith("/adminstats")) {
                if (!isAdmin(chatId)) { sendTelegram(chatId, "Not allowed."); return "OK"; }
                handleAdminStats(chatId);
                return "OK";
            }
            if (text.startsWith("/adminusers")) {
                if (!isAdmin(chatId)) { sendTelegram(chatId, "Not allowed."); return "OK"; }
                handleAdminUsers(chatId);
                return "OK";
            }
            if (text.startsWith("/admintop")) {
                if (!isAdmin(chatId)) { sendTelegram(chatId, "Not allowed."); return "OK"; }
                handleAdminTop(chatId);
                return "OK";
            }

            // Optional: allow custom uid ONLY if user has no uid yet
            if (text.startsWith("/link")) {
                handleCustomLink(chatId, text);
                return "OK";
            }

            return "OK";
        } catch (Exception ex) {
            ex.printStackTrace();
            return "OK";
        }
    }

    private boolean isAdmin(String chatId) {
        return StringUtils.hasText(adminChatId) && adminChatId.trim().equals(chatId);
    }

    private void handleAdminStats(String chatId) throws Exception {
        Integer totalUsers = jdbc.queryForObject("SELECT COUNT(*) FROM user_map", Integer.class);

        LocalDate today = LocalDate.now();
        Integer todayAlerts = jdbc.queryForObject(
                "SELECT COALESCE(SUM(alerts_count),0) FROM daily_usage WHERE day = ?",
                Integer.class,
                java.sql.Date.valueOf(today)
        );

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Admin Stats\n\n");
        sb.append("👤 Total users linked: ").append(totalUsers == null ? 0 : totalUsers).append("\n");
        sb.append("🔔 Alerts today: ").append(todayAlerts == null ? 0 : todayAlerts).append("\n\n");
        sb.append("Use /admintop for top users today\n");
        sb.append("Use /adminusers for latest linked users");

        sendTelegram(chatId, sb.toString());
    }

    private void handleAdminUsers(String chatId) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT uid, chat_id, updated_at FROM user_map ORDER BY updated_at DESC LIMIT 20"
        );

        StringBuilder sb = new StringBuilder();
        sb.append("👥 Latest 20 linked users\n\n");

        if (rows.isEmpty()) {
            sb.append("(no users yet)");
            sendTelegram(chatId, sb.toString());
            return;
        }

        for (Map<String, Object> r : rows) {
            sb.append("• UID: ").append(String.valueOf(r.get("uid")))
                    .append(" | chat_id: ").append(String.valueOf(r.get("chat_id")))
                    .append("\n");
        }

        sendTelegram(chatId, sb.toString());
    }

    private void handleAdminTop(String chatId) throws Exception {
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT um.uid, du.alerts_count " +
                        "FROM daily_usage du " +
                        "JOIN user_map um ON um.chat_id = du.chat_id " +
                        "WHERE du.day = ? " +
                        "ORDER BY du.alerts_count DESC " +
                        "LIMIT 10",
                java.sql.Date.valueOf(today)
        );

        StringBuilder sb = new StringBuilder();
        sb.append("🏆 Top 10 (today)\n\n");

        if (rows.isEmpty()) {
            sb.append("(no alerts today)");
            sendTelegram(chatId, sb.toString());
            return;
        }

        int rank = 1;
        for (Map<String, Object> r : rows) {
            sb.append(rank++).append(") ")
                    .append(String.valueOf(r.get("uid")))
                    .append(" — ")
                    .append(String.valueOf(r.get("alerts_count")))
                    .append("\n");
        }

        sendTelegram(chatId, sb.toString());
    }

    // ---------- Command handlers ----------

    private void handleStart(String chatId) throws Exception {
        UserLink existing = getByChatId(chatId);
        if (existing != null) {
            sendTelegram(chatId,
                    "You are already linked.\n\n" +
                            "UID: " + existing.uid + "\n\n" +
                            "Use /myuid to get your full Chartink webhook URL.\n" +
                            "Use /newuid to rotate it, or /unlink to remove.");
            return;
        }

        String uid = generateUniqueUid();
        String userKey = generateUniqueUserKey();

        linkUidToChat(uid, userKey, chatId);

        sendTelegram(chatId, buildLinkedMessage(uid, userKey));
    }

    private void handleMyUid(String chatId) throws Exception {
        UserLink existing = getByChatId(chatId);
        if (existing == null) {
            sendTelegram(chatId, "No UID linked.\nSend /start to generate one.");
            return;
        }
        sendTelegram(chatId, buildLinkedMessage(existing.uid, existing.userKey));
    }

    private void handleUnlink(String chatId, String text) throws Exception {
        if (!text.contains("confirm")) {
            sendTelegram(chatId, "⚠️ *Are you sure?*\n\n" +
                    "Unlinking will **permanently disable** all active alerts using your current UID.\n\n" +
                    "To proceed, send: `/unlink confirm` ");
            return;
        }
        jdbc.update("DELETE FROM user_map WHERE chat_id = ?", chatId);
        sendTelegram(chatId, "❌ *Unlinked.* Your previous webhook URL is now invalid.");
    }

    private void handleNewUid(String chatId, String text) throws Exception {
        // 1. Check for confirmation
        if (!text.contains("confirm")) {
            sendTelegram(chatId, "⚠️ *Rotate UID and Key?*\n\n" +
                    "This will generate a new URL. Your **existing alerts on Chartink will stop working** until you update them with the new URL.\n\n" +
                    "To proceed, send: `/newuid confirm` ");
            return;
        }

        // 2. Perform the rotation
        // Delete the old mapping first
        jdbc.update("DELETE FROM user_map WHERE chat_id = ?", chatId);

        // Generate new secure credentials
        String uid = generateUniqueUid();
        String userKey = generateUniqueUserKey();

        // Link the new credentials to the user
        linkUidToChat(uid, userKey, chatId);

        // Send the success message with the new URL
        sendTelegram(chatId, "🔄 *Rotated Successfully!*\n\n" + buildLinkedMessage(uid, userKey));
    }

    private void handleCustomLink(String chatId, String text) throws Exception {
        UserLink existing = getByChatId(chatId);
        if (existing != null) {
            sendTelegram(chatId,
                    "You already have a UID.\n\n" +
                            "UID: " + existing.uid + "\n\n" +
                            "Use /newuid to rotate or /unlink to remove.");
            return;
        }

        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendTelegram(chatId, "Usage: /link <your_uid>\nOr just use /start to auto-generate.");
            return;
        }

        String uid = parts[1].trim().toLowerCase();

        // basic validation
        if (!uid.matches("[a-z0-9_\\-]{3,20}")) {
            sendTelegram(chatId, "Invalid UID. Use 3-20 chars: a-z, 0-9, _ or -");
            return;
        }

        // ensure uid not taken
        if (getByUid(uid) != null) {
            sendTelegram(chatId, "This UID is already taken.\nChoose a different UID or use /start.");
            return;
        }

        String userKey = generateUniqueUserKey();
        linkUidToChat(uid, userKey, chatId);

        sendTelegram(chatId, buildLinkedMessage(uid, userKey));
    }

    // ---------- DB helpers ----------

    private static class UserLink {
        final String uid;
        final String chatId;
        final String userKey;

        UserLink(String uid, String chatId, String userKey) {
            this.uid = uid;
            this.chatId = chatId;
            this.userKey = userKey;
        }
    }

    private UserLink getByChatId(String chatId) {
        return jdbc.query(
                "SELECT uid, chat_id, user_key FROM user_map WHERE chat_id = ?",
                rs -> rs.next() ? new UserLink(rs.getString("uid"), rs.getString("chat_id"), rs.getString("user_key")) : null,
                chatId
        );
    }

    private UserLink getByUid(String uid) {
        return jdbc.query(
                "SELECT uid, chat_id, user_key FROM user_map WHERE uid = ?",
                rs -> rs.next() ? new UserLink(rs.getString("uid"), rs.getString("chat_id"), rs.getString("user_key")) : null,
                uid
        );
    }

    private void linkUidToChat(String uid, String userKey, String chatId) {
        jdbc.update(
                "INSERT INTO user_map(uid, chat_id, user_key, updated_at) VALUES(?,?,?,?)",
                uid, chatId, userKey, Instant.now().getEpochSecond()
        );
    }

    // ---------- UID & key generator ----------

    private String generateUniqueUid() throws Exception {
        for (int tries = 0; tries < 40; tries++) {
            String uid = generateFromAlphabet(UID_ALPHABET, 8).toLowerCase();
            if (getByUid(uid) == null) return uid;
        }
        throw new Exception("Could not generate unique UID");
    }

    private String generateUniqueUserKey() throws Exception {
        for (int tries = 0; tries < 40; tries++) {
            // Longer key for security
            String key = generateFromAlphabet(KEY_ALPHABET, 24);
            // ensure unique key
            String exists = jdbc.query(
                    "SELECT user_key FROM user_map WHERE user_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    key
            );
            if (exists == null) return key;
        }
        throw new Exception("Could not generate unique user key");
    }

    private String generateFromAlphabet(char[] alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet[RNG.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    // ---------- User-facing message ----------

    private String buildLinkedMessage(String uid, String userKey) {
        String base = StringUtils.hasText(publicUrl) ? publicUrl.trim() : "(server)";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String webhook = base + "/chartink?uid=" + uid + "&key=" + userKey;

        return "✅ *Linked successfully!*\n\n" +
                "🚀 *Your Webhook URL:*\n`" + webhook + "`\n\n" +
                "1. Copy the URL above.\n" +
                "2. Paste it into your Chartink Alert settings.\n\n" +
                "💡 *Commands:*\n" +
                "/myuid - Show your URL again\n" +
                "/stats - View daily usage\n" +
                "/more - Other actions (Reset/Unlink)";
    }

    // ---------- Alert message ----------

    private String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    private String buildMessage(String uid, String body) {
        if (body == null || body.trim().isEmpty()) {
            return "🔔 *Chartink Alert*\n\nNo extra data received.";
        }

        String scanName = "Manual/System Alert";
        String stockData = "";
        String timePart = "";

        try {
            // Check if the body is JSON (Common for raw Chartink webhooks)
            if (body.trim().startsWith("{")) {
                // Simple manual extraction if you don't want to add a JSON library
                stockData = extractJsonValue(body, "symbol");
                String price = extractJsonValue(body, "trigger_price");
                if (!price.isEmpty()) stockData += " @ " + price;

                scanName = extractJsonValue(body, "alert_name");
                timePart = extractJsonValue(body, "triggered_at");
            }
            // Fallback to your existing "Extra Data" parsing logic
            else if (body.toLowerCase().contains("extra data:")) {
                String extra = body.substring(body.toLowerCase().indexOf("extra data:") + 11).trim();
                String[] parts = extra.split(",");
                if (parts.length >= 1) scanName = parts[0].trim();
                if (parts.length >= 2) stockData = parts[1].trim();
                if (extra.contains("@")) timePart = extra.substring(extra.indexOf("@")).trim();
            } else {
                stockData = body.trim();
            }
        } catch (Exception e) {
            return "🔔 *Chartink Alert*\n\n" + escapeMarkdown(body);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔔 *Chartink Alert*").append("\n\n");
        if (!scanName.isEmpty()) sb.append("🧠 *Scan:* ").append(escapeMarkdown(scanName)).append("\n");
        if (!stockData.isEmpty()) sb.append("📈 *Stock:* ").append(escapeMarkdown(stockData)).append("\n");
        if (!timePart.isEmpty()) sb.append("⏰ *Time:* ").append(escapeMarkdown(timePart)).append("\n");

        return sb.toString().trim();
    }

    // Helper to extract values without adding heavy dependencies
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return "";

        start += pattern.length();
        // Skip opening quote if present
        if (json.charAt(start) == '"') start++;

        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);

        String value = json.substring(start, end).replace("\"", "").trim();
        return value;
    }

    // ---------- Telegram sender ----------

    private void sendTelegram(String chatId, String text) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

        String json = "{"
                + "\"chat_id\":\"" + escapeJson(chatId) + "\","
                + "\"text\":\"" + escapeJson(text) + "\","
                + "\"parse_mode\":\"Markdown\""
                + "}";

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            byte[] input = json.getBytes("UTF-8");
            os.write(input);
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                while (br.readLine() != null) { /* consume */ }
            }
        }
    }

    private void incrementTodayUsage(String chatId) {
        LocalDate today = LocalDate.now();

        jdbc.update(
                "INSERT INTO daily_usage(day, chat_id, alerts_count) VALUES(?,?,1) " +
                        "ON CONFLICT (day, chat_id) DO UPDATE SET alerts_count = daily_usage.alerts_count + 1",
                java.sql.Date.valueOf(today),
                chatId
        );
    }

    private int getTodayUsage(String chatId) {
        Integer v = jdbc.query(
                "SELECT count FROM alert_usage WHERE chat_id = ? AND day = CURRENT_DATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                chatId
        );
        return v == null ? 0 : v;
    }

    private int getTotalUsage(String chatId) {
        Integer v = jdbc.query(
                "SELECT COALESCE(SUM(count), 0) FROM alert_usage WHERE chat_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                chatId
        );
        return v == null ? 0 : v;
    }



    // Proper JSON escape
    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
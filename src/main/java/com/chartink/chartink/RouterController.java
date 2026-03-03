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

@RestController
public class RouterController {

    private final JdbcTemplate jdbc;

    // Dedup Telegram retries by update_id
    private final Set<Long> seenUpdates = ConcurrentHashMap.newKeySet();

    // UID generator
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no I,O,0,1

    @Value("${telegram.botToken}")
    private String botToken;

    @Value("${router.secret}")
    private String secret;

    public RouterController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 1) Chartink will call this
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
        if (secret == null || !secret.equals(key)) return "FORBIDDEN";

        String normalizedUid = uid.trim().toLowerCase();

        String chatId = jdbc.query(
                "SELECT chat_id FROM user_map WHERE uid = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                normalizedUid
        );

        if (chatId == null) return "UID_NOT_LINKED";

        String msg = buildMessage(normalizedUid, body);
        sendTelegram(chatId, msg);
        return "OK";
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
                handleUnlink(chatId);
                return "OK";
            }
            if (text.startsWith("/newuid")) {
                handleNewUid(chatId);
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

    // ---------- Command handlers ----------

    private void handleStart(String chatId) throws Exception {
        String existingUid = getUidByChatId(chatId);
        if (existingUid != null) {
            sendTelegram(chatId,
                    "You already have a UID:\n" + existingUid +
                            "\n\nUse /myuid to view it.\nUse /unlink then /start to get a new one.");
            return;
        }
        String uid = generateUniqueUid();
        linkUidToChat(uid, chatId);
        sendTelegram(chatId, buildLinkedMessage(uid));
    }

    private void handleMyUid(String chatId) throws Exception {
        String existingUid = getUidByChatId(chatId);
        if (existingUid == null) {
            sendTelegram(chatId, "No UID linked.\nSend /start to generate one.");
        } else {
            sendTelegram(chatId, "Your UID: " + existingUid);
        }
    }

    private void handleUnlink(String chatId) throws Exception {
        String existingUid = getUidByChatId(chatId);
        if (existingUid == null) {
            sendTelegram(chatId, "You don't have any UID linked.");
            return;
        }
        jdbc.update("DELETE FROM user_map WHERE chat_id = ?", chatId);
        sendTelegram(chatId, "Unlinked UID: " + existingUid + "\nSend /start to generate a new UID.");
    }

    private void handleNewUid(String chatId) throws Exception {
        // unlink if exists
        jdbc.update("DELETE FROM user_map WHERE chat_id = ?", chatId);
        String uid = generateUniqueUid();
        linkUidToChat(uid, chatId);
        sendTelegram(chatId, "New UID generated!\n\n" + buildLinkedMessage(uid));
    }

    private void handleCustomLink(String chatId, String text) throws Exception {
        String existingUid = getUidByChatId(chatId);
        if (existingUid != null) {
            sendTelegram(chatId,
                    "You already have UID: " + existingUid +
                            "\nUse /unlink first, or just use /newuid.");
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

        String takenBy = getChatIdByUid(uid);
        if (takenBy != null) {
            sendTelegram(chatId, "This UID is already taken.\nChoose a different UID or use /start.");
            return;
        }

        // Link (DB unique index on chat_id prevents multiple UIDs per chat too)
        linkUidToChat(uid, chatId);
        sendTelegram(chatId, buildLinkedMessage(uid));
    }

    // ---------- DB helpers ----------

    private String getUidByChatId(String chatId) {
        return jdbc.query(
                "SELECT uid FROM user_map WHERE chat_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                chatId
        );
    }

    private String getChatIdByUid(String uid) {
        return jdbc.query(
                "SELECT chat_id FROM user_map WHERE uid = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                uid
        );
    }

    private void linkUidToChat(String uid, String chatId) {
        jdbc.update(
                "INSERT INTO user_map(uid, chat_id, updated_at) VALUES(?,?,?)",
                uid, chatId, Instant.now().getEpochSecond()
        );
    }

    // ---------- UID generator ----------

    private String generateUniqueUid() throws Exception {
        for (int tries = 0; tries < 30; tries++) {
            String uid = generateUid(8);
            if (getChatIdByUid(uid) == null) {
                return uid;
            }
        }
        throw new Exception("Could not generate unique UID");
    }

    private String generateUid(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        }
        return sb.toString().toLowerCase();
    }

    private String buildLinkedMessage(String uid) {
        return "Linked!\nUID: " + uid +
                "\n\nNow set your Chartink webhook to:\n" +
                "(server)/chartink?uid=" + uid + "&key=YOUR_SECRET";
    }

    // ---------- Alert message ----------

    private String buildMessage(String uid, String body) {
        if (body == null || body.trim().isEmpty()) {
            return "Chartink Alert Triggered\nUID: " + uid;
        }

        String scanName = "";
        String stockData = "";

        try {
            // Example: Extra Data: iffl 2, MTARTECH - 2114, @ 12:09 pm
            String lower = body.toLowerCase();
            int idx = lower.indexOf("extra data:");
            if (idx >= 0) {
                String extra = body.substring(idx + "extra data:".length()).trim();
                String[] parts = extra.split(",");
                if (parts.length >= 2) {
                    scanName = parts[0].trim();
                    stockData = parts[1].trim();
                } else {
                    stockData = extra.trim();
                }
            } else {
                stockData = body.trim();
            }
        } catch (Exception e) {
            return "Alert\n\n" + body;
        }

        // Plain text (Java 8 safe)
        StringBuilder sb = new StringBuilder();
        sb.append("Chartink Alert").append("\n");
        sb.append("UID: ").append(uid).append("\n\n");
        if (!scanName.isEmpty()) sb.append("Scan: ").append(scanName).append("\n");
        if (!stockData.isEmpty()) sb.append("Stock: ").append(stockData);
        return sb.toString();
    }

    // ---------- Telegram sender ----------

    private void sendTelegram(String chatId, String text) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

        String json = "{"
                + "\"chat_id\":\"" + escapeJson(chatId) + "\","
                + "\"text\":\"" + escapeJson(text) + "\""
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

    // Proper JSON escape for Java 8
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
package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;

/** Чистый маршрутизатор обновлений MAX Bot API: не зависит от Bukkit и сети. */
public final class MaxBotRouter {

    public interface Backend {
        /** Код подтверждения привязки из личного сообщения. */
        String onCode(String senderKey, long userId, String code, long nowMillis);

        /** Обнаружен chat_id из bot_added / bot_started (GET /chats больше нет). */
        void onChatDiscovered(long chatId, String chatType, long nowMillis);

        /** Сообщение в подключённой беседе/канале для моста в игровой чат. */
        void onBridgeMessage(long chatId, long userId, String senderName, String text, long nowMillis);

        /** Нажатие inline-кнопки. */
        String onCallback(String senderKey, long userId, String payload, long nowMillis);

        /** Ответ в личный диалог пользователя. */
        void sendDirectReply(long userId, String text);

        /** Текущий chat_id моста; 0 — мост не настроен. */
        long bridgeChatId();
    }

    private final Backend backend;

    public MaxBotRouter(Backend backend) {
        this.backend = backend;
    }

    public void handle(JsonObject update, long nowMillis) {
        if (update == null) return;
        String type = type(update);
        switch (type) {
            case "message_created" -> handleMessage(update, nowMillis);
            case "message_callback" -> handleCallback(update, nowMillis);
            case "bot_started", "bot_added", "bot_removed" -> {
                long chatId = chatId(update);
                if (chatId > 0L) {
                    backend.onChatDiscovered(chatId, text(update, "chat_type"), nowMillis);
                }
                if (type.equals("bot_started")) {
                    long userId = longField(update, "user_id");
                    if (userId > 0L) {
                        String reply = backend.onCallback("max:" + userId, userId, "start", nowMillis);
                        if (reply != null && !reply.isBlank()) backend.sendDirectReply(userId, reply);
                    }
                }
            }
            default -> { /* остальные типы игнорируем безопасно */ }
        }
    }

    private void handleMessage(JsonObject update, long nowMillis) {
        JsonObject message = object(update, "message");
        if (message == null) message = update;
        JsonObject sender = object(message, "sender");
        JsonObject recipient = object(message, "recipient");
        long userId = sender == null ? longField(message, "user_id") : longField(sender, "user_id");
        if (userId <= 0L) return; // сообщения от ботов/сообществ не создают эхо-петлю
        long chatId = recipient == null ? longField(message, "chat_id") : longField(recipient, "chat_id");
        String chatType = recipient == null ? text(message, "chat_type") : text(recipient, "chat_type");
        String body = textOf(message);
        if (body.isEmpty()) return;
        String senderKey = "max:" + userId;
        String trimmed = body.trim();

        // Код привязки принимаем ТОЛЬКО из личного диалога (диалог, а не беседа/канал).
        boolean direct = chatId <= 0L || isDirect(chatType) || chatId == userId;
        if (direct && looksLikeCode(trimmed)) {
            String reply = backend.onCode(senderKey, userId, codeFrom(trimmed), nowMillis);
            if (reply != null && !reply.isBlank()) backend.sendDirectReply(userId, reply);
            return;
        }
        if (chatId > 0L && chatId == backend.bridgeChatId()) {
            String name = sender == null ? "" : text(sender, "name");
            if (name.isEmpty()) name = "MAX";
            backend.onBridgeMessage(chatId, userId, name, trimmed, nowMillis);
        }
    }

    private void handleCallback(JsonObject update, long nowMillis) {
        JsonObject callback = object(update, "callback");
        JsonObject user = object(update, "user");
        long userId = user == null ? longField(update, "user_id") : longField(user, "user_id");
        if (userId <= 0L) return;
        String payload = callback == null ? text(update, "payload") : text(callback, "payload");
        if (payload.isEmpty()) return;
        String reply = backend.onCallback("max:" + userId, userId, payload, nowMillis);
        if (reply != null && !reply.isBlank()) backend.sendDirectReply(userId, reply);
    }

    /** Код принимаем одиночным словом, либо после «/link», «код», «code». */
    public static boolean looksLikeCode(String text) {
        return !codeFrom(text).isEmpty();
    }

    public static String codeFrom(String text) {
        if (text == null) return "";
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 1) return SocialLinkCodeManager.normalize(parts[0]);
        if (parts.length == 2) {
            String head = parts[0].toLowerCase(java.util.Locale.ROOT).replaceFirst("^/", "");
            if (head.equals("link") || head.equals("код") || head.equals("code") || head.equals("привязать")) {
                return SocialLinkCodeManager.normalize(parts[1]);
            }
        }
        return "";
    }

    public static String type(JsonObject update) {
        String value = text(update, "update_type");
        return value.isEmpty() ? text(update, "type") : value;
    }

    public static long chatId(JsonObject update) {
        long direct = longField(update, "chat_id");
        if (direct > 0L) return direct;
        JsonObject message = object(update, "message");
        JsonObject recipient = message == null ? null : object(message, "recipient");
        return recipient == null ? 0L : longField(recipient, "chat_id");
    }

    private static boolean isDirect(String chatType) {
        return chatType.isEmpty() || chatType.equalsIgnoreCase("dialog");
    }

    private static String textOf(JsonObject message) {
        JsonObject body = object(message, "body");
        if (body != null) {
            String value = text(body, "text");
            if (!value.isEmpty()) return value;
        }
        return text(message, "text");
    }

    private static JsonObject object(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject()
                ? json.getAsJsonObject(key) : null;
    }

    private static String text(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return "";
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException bad) {
            return "";
        }
    }

    private static long longField(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return 0L;
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException bad) {
            return 0L;
        }
    }
}

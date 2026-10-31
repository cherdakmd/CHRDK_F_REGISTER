package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * Чистый маршрутизатор входящих уведомлений Bots Long Poll ВКонтакте
 * (не зависит от классов сервера Minecraft и полностью проверяется в модульных тестах).
 */
public final class VkBotRouter {

    public interface Backend {
        String handleLinkCode(long vkUserId, String code, long nowMillis);

        String formatServerOnline();

        String formatAccountProfile(long vkUserId, long nowMillis);

        String handleRemoteLogout(long vkUserId);

        String handleRemoteFreeze(long vkUserId, boolean freeze);

        String handleToggleNotifications(long vkUserId);

        String handleUnlinkFromVk(long vkUserId);

        void sendDirectReply(long peerId, String text);

        void onGroupJoin(long vkUserId, long nowMillis);

        void onGroupLeave(long vkUserId);

        void onDonutActive(long vkUserId, long nowMillis);

        void onDonutEnded(long vkUserId, long nowMillis);

        void onWallPost(VkApiClient.WallPost post);
    }

    private final Backend backend;

    public VkBotRouter(Backend backend) {
        this.backend = backend;
    }

    public void handleUpdate(JsonObject update, long nowMillis) {
        if (update == null || !update.has("type") || update.get("type").isJsonNull()) {
            return;
        }
        String type = update.get("type").getAsString();
        JsonObject object = update.has("object") && update.get("object").isJsonObject()
                ? update.getAsJsonObject("object") : null;
        if (object == null) {
            return;
        }
        switch (type) {
            case "message_new" -> handleMessageNew(object, nowMillis);
            case "group_join" -> {
                long userId = longField(object, "user_id");
                if (userId > 0L) backend.onGroupJoin(userId, nowMillis);
            }
            case "group_leave" -> {
                long userId = longField(object, "user_id");
                if (userId > 0L) backend.onGroupLeave(userId);
            }
            case "donut_subscription_create", "donut_subscription_prolonged" -> {
                long userId = longField(object, "user_id");
                if (userId > 0L) backend.onDonutActive(userId, nowMillis);
            }
            case "donut_subscription_expired", "donut_subscription_cancelled" -> {
                long userId = longField(object, "user_id");
                if (userId > 0L) backend.onDonutEnded(userId, nowMillis);
            }
            case "wall_post_new" -> {
                VkApiClient.WallPost post = VkApiClient.parseWallPost(object);
                if (post.id() > 0L) {
                    backend.onWallPost(post);
                }
            }
            default -> {
                // Прочие уведомления сообщества игнорируем безопасно.
            }
        }
    }

    private void handleMessageNew(JsonObject object, long nowMillis) {
        JsonObject message = object.has("message") && object.get("message").isJsonObject()
                ? object.getAsJsonObject("message") : object;
        long peerId = longField(message, "peer_id");
        long fromId = longField(message, "from_id");
        // Личные команды привязки и защиты принимаются строго в личном диалоге пользователя с группой.
        if (fromId <= 0L || peerId != fromId) {
            return;
        }
        String rawText = message.has("text") && !message.get("text").isJsonNull()
                ? message.get("text").getAsString().trim() : "";
        if (rawText.isEmpty()) {
            return;
        }
        String reply = dispatchDirectCommand(fromId, rawText, nowMillis);
        if (reply != null && !reply.isBlank()) {
            backend.sendDirectReply(peerId, reply);
        }
    }

    public String dispatchDirectCommand(long vkUserId, String rawText, long nowMillis) {
        String trimmed = rawText == null ? "" : rawText.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+");
        String first = stripPrefix(parts[0]).toLowerCase(Locale.ROOT);

        // Защита: любые попытки смены или сброса пароля через ВК отклоняются явно.
        if (isPasswordMutationAttempt(first, trimmed)) {
            return "🔒 Из соображений безопасности смена и сброс пароля через ВКонтакте запрещены.\n"
                    + "Если вы подозреваете взлом, отправьте /заморозить — бот сбросит все сессии и заблокирует вход в аккаунт.";
        }

        return switch (first) {
            case "начать", "start", "помощь", "help", "команды" -> helpText();
            case "онлайн", "online", "сервер" -> backend.formatServerOnline();
            case "профиль", "profile", "статус", "status", "аккаунт" -> backend.formatAccountProfile(vkUserId, nowMillis);
            case "выход", "logout", "кик", "сессии" -> backend.handleRemoteLogout(vkUserId);
            case "заморозить", "freeze", "блок", "lock" -> backend.handleRemoteFreeze(vkUserId, true);
            case "разморозить", "unfreeze", "разблок", "unlock" -> backend.handleRemoteFreeze(vkUserId, false);
            case "уведомления", "notify", "alerts" -> backend.handleToggleNotifications(vkUserId);
            case "отвязать", "unlink" -> backend.handleUnlinkFromVk(vkUserId);
            case "привязать", "link", "код", "code" -> {
                if (parts.length < 2) {
                    yield "Введите в игре команду /vk link и отправьте полученный одноразовый код сюда.";
                }
                yield backend.handleLinkCode(vkUserId, parts[1], nowMillis);
            }
            default -> {
                // Если прислан сам одноразовый код одним словом — принимаем его сразу без префикса.
                if (parts.length == 1 && !VkLinkCodeManager.normalizeCode(parts[0]).isEmpty()) {
                    yield backend.handleLinkCode(vkUserId, parts[0], nowMillis);
                }
                yield helpText();
            }
        };
    }

    private static boolean isPasswordMutationAttempt(String cmd, String fullText) {
        for (String token : List.of("пароль", "password", "changepassword", "resetpassword", "changepass", "восстановить")) {
            if (cmd.equals(token)) {
                return true;
            }
        }
        String lower = fullText.toLowerCase(Locale.ROOT);
        return lower.contains("сбросить пароль") || lower.contains("сменить пароль")
                || lower.contains("забыл пароль") || lower.contains("восстановить пароль");
    }

    private static String stripPrefix(String word) {
        if (word.startsWith("/") || word.startsWith("!")) {
            return word.substring(1);
        }
        return word;
    }

    private static String helpText() {
        return """
                Команды бота CHRDK:
                • <код> — привязать аккаунт (получите код в игре: /vk link)
                • /онлайн — текущий онлайн и состояние сервера
                • /профиль — статус вашего аккаунта, ранга и VK Donut
                • /выход — сбросить активные сессии и отключить персонажа
                • /заморозить — заблокировать вход в аккаунт
                • /разморозить — снова разрешить вход в аккаунт
                • /уведомления — переключить уведомления о входах
                • /отвязать — отвязать профиль ВКонтакте
                Пароли через ВКонтакте никогда не меняются и не сбрасываются.""";
    }

    private static long longField(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(name).getAsLong();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }
}

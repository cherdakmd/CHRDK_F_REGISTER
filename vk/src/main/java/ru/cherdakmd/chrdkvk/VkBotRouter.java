package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Чистый маршрутизатор входящих уведомлений Bots Long Poll ВКонтакте
 * (не зависит от классов сервера Minecraft и полностью проверяется в модульных тестах).
 */
public final class VkBotRouter {

    /** Префикс упоминания бота в беседе ВК: {@code [club12345|@chrdk_reborn] текст}. */
    private static final Pattern LEADING_BOT_MENTION =
            Pattern.compile("^\\[(?:club|public)\\d+\\|[^\\]]+]\\s*,?\\s*", Pattern.CASE_INSENSITIVE);

    public interface Backend {
        String handleLinkCode(long vkUserId, String code, long nowMillis);

        String formatServerOnline();

        default String formatWikiLinks() {
            return "📖 Полевой справочник сервера: " + VkConfig.DEFAULT_WIKI_URL
                    + "\n💬 Сообщество ВКонтакте: " + VkConfig.DEFAULT_COMMUNITY_URL;
        }

        String formatAccountProfile(long vkUserId, long nowMillis);

        String handleRemoteLogout(long vkUserId);

        String handleRemoteFreeze(long vkUserId, boolean freeze);

        String handleToggleNotifications(long vkUserId);

        String handleUnlinkFromVk(long vkUserId);

        void sendDirectReply(long peerId, String text);

        default void sendDirectReplyWithMenu(long peerId, String text) {
            sendDirectReply(peerId, text);
        }

        void onGroupJoin(long vkUserId, long nowMillis);

        void onGroupLeave(long vkUserId);

        void onDonutActive(long vkUserId, long nowMillis);

        void onDonutEnded(long vkUserId, long nowMillis);

        void onWallPost(VkApiClient.WallPost post);

        /** Активный {@code peer_id} привязанной беседы сообщества ВК ({@code >= 2000000001}, иначе {@code 0}). */
        default long activeGroupChatPeerId() {
            return 0L;
        }

        /** Разрешены ли справочные команды ({@code /онлайн}, {@code /вики}, {@code /chatid}) внутри беседы ВК. */
        default boolean groupChatInfoCommandsEnabled() {
            return true;
        }

        /** Принято обычное сообщение из привязанной беседы сообщества ВК для моста в игру. */
        default void onGroupChatMessage(long peerId, long fromUserId, JsonObject message, long nowMillis) {
        }
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
        long out = longField(message, "out");
        // Игнорируем исходящие сообщения и сообщения от ботов/сообществ (from_id <= 0) — защита от эхо-петель.
        if (fromId <= 0L || out == 1L) {
            return;
        }

        // 1. Беседа сообщества ВКонтакте (peer_id >= 2000000001).
        if (peerId >= VkConfig.MIN_CONVERSATION_PEER_ID) {
            handleGroupConversationMessage(peerId, fromId, message, nowMillis);
            return;
        }

        // 2. Личный диалог пользователя с группой (peer_id == from_id):
        // коды привязки, кнопки самозащиты и команды (/выход, /заморозить) работают СТРОГО здесь.
        if (peerId != fromId) {
            return;
        }
        String payloadRaw = "";
        if (message.has("payload") && !message.get("payload").isJsonNull()) {
            var payloadEl = message.get("payload");
            payloadRaw = payloadEl.isJsonPrimitive() ? payloadEl.getAsString() : payloadEl.toString();
        }
        String payloadCmd = VkKeyboards.extractCommandFromPayload(payloadRaw);
        String rawText = !payloadCmd.isEmpty()
                ? payloadCmd
                : (message.has("text") && !message.get("text").isJsonNull()
                ? message.get("text").getAsString().trim() : "");
        if (rawText.isEmpty()) {
            return;
        }
        String reply = dispatchDirectCommand(fromId, rawText, nowMillis);
        if (reply != null && !reply.isBlank()) {
            backend.sendDirectReplyWithMenu(peerId, reply);
        }
    }

    private void handleGroupConversationMessage(long peerId, long fromId, JsonObject message, long nowMillis) {
        String rawText = message.has("text") && !message.get("text").isJsonNull()
                ? message.get("text").getAsString().trim() : "";
        String stripped = LEADING_BOT_MENTION.matcher(rawText).replaceFirst("").trim();

        if (!stripped.isEmpty() && (stripped.charAt(0) == '/' || stripped.charAt(0) == '!')) {
            String cmd = stripPrefix(stripped.split("\\s+")[0]).toLowerCase(Locale.ROOT);
            if (backend.groupChatInfoCommandsEnabled()) {
                if (cmd.equals("chatid") || cmd.equals("peerid") || cmd.equals("idбеседы")) {
                    backend.sendDirectReply(peerId, "💬 ID этой беседы (peer_id): " + peerId
                            + "\nЧтобы подключить мост глобального чата на сервере: /vkadmin setchat " + peerId);
                    return;
                }
                if (peerId == backend.activeGroupChatPeerId()) {
                    if (cmd.equals("онлайн") || cmd.equals("online") || cmd.equals("сервер")
                            || cmd.equals("кто") || cmd.equals("list")) {
                        String online = backend.formatServerOnline();
                        if (online != null && !online.isBlank()) {
                            backend.sendDirectReply(peerId, online);
                        }
                        return;
                    }
                    if (cmd.equals("вики") || cmd.equals("wiki") || cmd.equals("гайд") || cmd.equals("сайт")) {
                        String wiki = backend.formatWikiLinks();
                        if (wiki != null && !wiki.isBlank()) {
                            backend.sendDirectReply(peerId, wiki);
                        }
                        return;
                    }
                }
            }
            // Любые слэш-команды (например, ошибочно введённые /выход или /привязать)
            // никогда не выполняются в общей беседе и не пересылаются в игровой чат.
            return;
        }

        if (peerId == backend.activeGroupChatPeerId()) {
            backend.onGroupChatMessage(peerId, fromId, message, nowMillis);
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
            case "начать", "start", "помощь", "help", "команды", "меню" -> helpText();
            case "онлайн", "online", "сервер" -> backend.formatServerOnline();
            case "вики", "wiki", "гайд", "сайт" -> backend.formatWikiLinks();
            case "профиль", "profile", "статус", "status", "аккаунт" -> backend.formatAccountProfile(vkUserId, nowMillis);
            case "выход", "logout", "кик", "сессии" -> backend.handleRemoteLogout(vkUserId);
            case "заморозить", "freeze", "блок", "lock" -> backend.handleRemoteFreeze(vkUserId, true);
            case "разморозить", "unfreeze", "разблок", "unlock" -> backend.handleRemoteFreeze(vkUserId, false);
            case "уведомления", "notify", "alerts" -> backend.handleToggleNotifications(vkUserId);
            case "отвязать", "unlink" -> backend.handleUnlinkFromVk(vkUserId);
            case "привязать", "link", "код", "code" -> {
                if (parts.length < 2) {
                    yield "Введите в игре команду /vk link, получите одноразовый код и отправьте его сюда.";
                }
                yield backend.handleLinkCode(vkUserId, parts[1], nowMillis);
            }
            default -> {
                if (parts.length == 1 && looksLikeOtpCode(parts[0])) {
                    yield backend.handleLinkCode(vkUserId, parts[0], nowMillis);
                }
                yield helpText();
            }
        };
    }

    static boolean isPasswordMutationAttempt(String firstWord, String fullText) {
        String lowerFull = fullText.toLowerCase(Locale.ROOT);
        if (List.of("пароль", "password", "pass", "changepassword", "resetpassword",
                "сброспароля", "сменитьпароль", "восстановить", "recovery").contains(firstWord)) {
            return true;
        }
        return lowerFull.contains("парол")
                || lowerFull.contains("reset pass")
                || lowerFull.contains("change pass")
                || lowerFull.contains("forgot pass");
    }

    static boolean looksLikeOtpCode(String token) {
        String norm = VkLinkCodeManager.normalizeCode(token);
        if (norm.length() < 4 || norm.length() > 10) {
            return false;
        }
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '2' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String stripPrefix(String word) {
        if (word.startsWith("/") || word.startsWith("!")) {
            return word.substring(1);
        }
        return word;
    }

    private static long longField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException badNumber) {
            return 0L;
        }
    }

    public static String helpText() {
        return """
                👋 Бот сообщества CHRDK:
                • Привязка: введите /vk link в игре и пришлите полученный код сюда.
                • /профиль — статус привязки, подписки и VK Donut
                • /онлайн — текущий онлайн сервера и список игроков
                • /вики — полевой справочник и ссылки сервера
                • /уведомления — вкл/выкл уведомления о входах
                • /выход — сбросить сохранённые сессии и кикнуть активный сеанс
                • /заморозить — заблокировать вход в аккаунт
                • /разморозить — снять блокировку входа
                • /отвязать — отвязать страницу ВК от аккаунта
                🔒 Пароли и 2FA через ВКонтакте никогда не меняются и не сбрасываются.""";
    }
}

package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Генератор интерактивных клавиатур VK Bots API (v5.199) и разборщик {@code message.payload}.
 *
 * <p>Не зависит от Bukkit: JSON-схема кнопок и маршрутизация payload проверяются юнит-тестами.</p>
 */
public final class VkKeyboards {

    private VkKeyboards() {
    }

    /** Постоянное меню личных сообщений с группой ВКонтакте. */
    public static String directMenuKeyboard() {
        JsonObject root = new JsonObject();
        root.addProperty("one_time", false);
        root.addProperty("inline", false);

        JsonArray rows = new JsonArray();
        rows.add(row(
                textButton("👤 Профиль", "profile", "primary"),
                textButton("🌐 Онлайн", "online", "secondary")
        ));
        rows.add(row(
                textButton("🔔 Уведомления", "notify", "secondary"),
                textButton("📖 Вики", "wiki", "secondary")
        ));
        rows.add(row(
                textButton("🚪 Выход сессий", "logout", "secondary"),
                textButton("🧊 Заморозить", "freeze", "negative"),
                textButton("🔥 Разморозить", "unfreeze", "positive")
        ));
        root.add("buttons", rows);
        return root.toString();
    }

    /**
     * Встроенные (inline) кнопки экстренной самозащиты прямо под уведомлением о входе
     * или о попытке подбора пароля: нажатие в один тап с телефона сбрасывает сессии или замораживает вход.
     */
    public static String securityAlertInlineKeyboard() {
        JsonObject root = new JsonObject();
        root.addProperty("inline", true);

        JsonArray rows = new JsonArray();
        rows.add(row(
                textButton("🚪 Сбросить сессии", "logout", "secondary"),
                textButton("🧊 Заморозить вход", "freeze", "negative")
        ));
        root.add("buttons", rows);
        return root.toString();
    }

    /**
     * Извлекает каноническую команду бота из JSON-поля {@code message.payload} кнопки ВК.
     * Возвращает пустую строку, если payload отсутствует или не распознан.
     */
    public static String extractCommandFromPayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawPayload);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject obj = parsed.getAsJsonObject();
            String cmd = "";
            if (obj.has("cmd") && obj.get("cmd").isJsonPrimitive()) {
                cmd = obj.get("cmd").getAsString().trim().toLowerCase(Locale.ROOT);
            } else if (obj.has("command") && obj.get("command").isJsonPrimitive()) {
                cmd = obj.get("command").getAsString().trim().toLowerCase(Locale.ROOT);
            }
            return switch (cmd) {
                case "profile" -> "/профиль";
                case "online" -> "/онлайн";
                case "notify" -> "/уведомления";
                case "wiki" -> "/вики";
                case "logout" -> "/выход";
                case "freeze" -> "/заморозить";
                case "unfreeze" -> "/разморозить";
                case "help", "start" -> "/помощь";
                default -> "";
            };
        } catch (RuntimeException malformed) {
            return "";
        }
    }

    private static JsonArray row(JsonObject... buttons) {
        JsonArray arr = new JsonArray();
        for (JsonObject btn : buttons) {
            arr.add(btn);
        }
        return arr;
    }

    private static JsonObject textButton(String label, String cmd, String color) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", cmd);

        JsonObject action = new JsonObject();
        action.addProperty("type", "text");
        action.addProperty("label", label);
        action.addProperty("payload", payload.toString());

        JsonObject button = new JsonObject();
        button.add("action", action);
        button.addProperty("color", color);
        return button;
    }
}

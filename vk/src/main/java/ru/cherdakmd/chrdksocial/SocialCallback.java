package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Единая схема payload для callback-кнопок VK ({@code message_event}) и MAX
 * ({@code message_callback}). Провайдеры отличаются только транспортом, но
 * разбирают одни и те же действия — это и есть общий слой интерактива.
 */
public final class SocialCallback {

    public record Action(String kind, String arg) {
        public boolean known() {
            return !kind.isEmpty();
        }
    }

    public record Button(String label, String kind, String arg, String color) {
        public String payload() {
            JsonObject obj = new JsonObject();
            obj.addProperty("act", kind);
            if (arg != null && !arg.isEmpty()) obj.addProperty("arg", arg);
            return obj.toString();
        }
    }

    public static final Action UNKNOWN = new Action("", "");

    private SocialCallback() {
    }

    /** Поддерживает и новую схему {@code act}, и старую {@code cmd}. */
    public static Action parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) return UNKNOWN;
        try {
            JsonObject obj = JsonParser.parseString(rawPayload).getAsJsonObject();
            String kind = field(obj, "act");
            String arg = field(obj, "arg");
            if (kind.isEmpty()) {
                kind = field(obj, "cmd");
                if (arg.isEmpty()) arg = field(obj, "command");
            }
            kind = kind.toLowerCase(Locale.ROOT);
            return switch (kind) {
                case "confirm", "cancel", "link_wizard", "ticket", "poll", "help",
                     "request", "poll_new" -> new Action(kind, arg);
                case "start" -> new Action("help", "");
                case "link", "код" -> new Action("link_wizard", "");
                case "profile", "status" -> new Action("help", "");
                default -> UNKNOWN;
            };
        } catch (RuntimeException malformed) {
            return UNKNOWN;
        }
    }

    private static String field(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) return "";
        try {
            return obj.get(key).getAsString().trim();
        } catch (RuntimeException bad) {
            return "";
        }
    }
}

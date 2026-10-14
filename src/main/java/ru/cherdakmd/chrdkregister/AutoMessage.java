package ru.cherdakmd.chrdkregister;

import java.util.Map;
import java.util.function.Predicate;

/** Legacy strings still work; new maps support optional addon/permission and personal conditions. */
record AutoMessage(String text, String plugin, String permission, String condition) {
    static AutoMessage parse(Object value) {
        if (value instanceof String text) return new AutoMessage(text, "", "", "always");
        if (!(value instanceof Map<?, ?> map)) return null;
        String text = string(map.get("text"));
        String condition = string(map.get("condition"));
        if (condition.isBlank()) condition = "always";
        if (text.isBlank() || !java.util.Set.of("always", "trial", "unsecured").contains(condition)) return null;
        return new AutoMessage(text, string(map.get("plugin")), string(map.get("permission")), condition);
    }
    private static String string(Object obj) { return obj == null ? "" : obj.toString().trim(); }
    boolean eligible(Predicate<String> plugins, Predicate<String> permissions, boolean trial, boolean unsecured) {
        return (plugin.isBlank() || plugins.test(plugin)) && (permission.isBlank() || permissions.test(permission))
                && switch (condition) { case "always" -> true; case "trial" -> trial; case "unsecured" -> unsecured; default -> false; };
    }
}

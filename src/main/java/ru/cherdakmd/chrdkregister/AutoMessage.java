package ru.cherdakmd.chrdkregister;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Одно автосообщение из {@code auto-messages.messages}.
 *
 * <p>Старые строки ({@code - "текст"}) работают как раньше. Карта позволяет
 * адресно показать сообщение: {@code plugin} — только когда аддон включён,
 * {@code permission} — только её владельцам, {@code condition} — по состоянию
 * игрока, {@code min-online} — только на «живом» сервере.</p>
 *
 * <p>Условия: {@code always} (по умолчанию), {@code trial} (пробная проходка),
 * {@code unsecured} (2FA не подключена), {@code no-pass} (проходки нет),
 * {@code paid} (активная непробная проходка). Неизвестное условие — сообщение
 * отбрасывается при загрузке: опечатка не должна превращаться в спам всем.</p>
 *
 * @param text      MiniMessage-текст
 * @param plugin    имя аддона, без которого сообщение не показывается ("" — всегда)
 * @param permission право для получателя ("" — без проверки)
 * @param condition условие по состоянию игрока
 * @param minOnline минимум игроков онлайн (0 — без ограничения)
 */
record AutoMessage(String text, String plugin, String permission, String condition, int minOnline) {

    static final Set<String> CONDITIONS = Set.of("always", "trial", "unsecured", "no-pass", "paid");

    AutoMessage {
        plugin = plugin == null ? "" : plugin.trim();
        permission = permission == null ? "" : permission.trim();
        condition = condition == null || condition.isBlank()
                ? "always" : condition.trim().toLowerCase(java.util.Locale.ROOT);
        minOnline = Math.clamp(minOnline, 0, 10_000);
    }

    static AutoMessage parse(Object value) {
        if (value instanceof String text) {
            return new AutoMessage(GameplayRewardTexts.announcement(text), "", "", "always", 0);
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String text = string(map.get("text"));
        if (text.isBlank()) {
            return null;
        }
        String condition = string(map.get("condition"));
        if (condition.isBlank()) {
            condition = "always";
        }
        condition = condition.toLowerCase(java.util.Locale.ROOT);
        if (!CONDITIONS.contains(condition)) {
            return null;
        }
        return new AutoMessage(GameplayRewardTexts.announcement(text), string(map.get("plugin")), string(map.get("permission")),
                condition, number(map.get("min-online")));
    }

    /** Совместимость: проверка без состояния проходки (старые вызовы и тесты). */
    boolean eligible(Predicate<String> plugins, Predicate<String> permissions,
                     boolean trial, boolean unsecured) {
        return eligible(plugins, permissions, trial, unsecured, true, false, 0);
    }

    boolean eligible(Predicate<String> plugins, Predicate<String> permissions,
                     boolean trial, boolean unsecured, boolean hasPass, boolean paid, int online) {
        if (minOnline > 0 && online < minOnline) {
            return false;
        }
        if (!plugin.isBlank() && !plugins.test(plugin)) {
            return false;
        }
        if (!permission.isBlank() && !permissions.test(permission)) {
            return false;
        }
        return switch (condition) {
            case "always" -> true;
            case "trial" -> trial;
            case "unsecured" -> unsecured;
            case "no-pass" -> !hasPass;
            case "paid" -> paid;
            default -> false;
        };
    }

    private static String string(Object obj) {
        return obj == null ? "" : obj.toString().trim();
    }

    private static int number(Object obj) {
        if (obj instanceof Number number) {
            return number.intValue();
        }
        if (obj != null) {
            try {
                return Integer.parseInt(obj.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}

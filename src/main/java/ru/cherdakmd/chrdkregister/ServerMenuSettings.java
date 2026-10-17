package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Настройки серверного меню {@code /menu} (секция {@code server-menu:}):
 * полностью конфиг-driven — владелец сервера сам пробрасывает пункты всех аддонов,
 * ядро аддоны не требует (соff-проволока: клик просто выполняет {@code performCommand}).
 *
 * @param enabled мастер-флаг меню
 * @param entries пункты меню
 */
public record ServerMenuSettings(boolean enabled, List<ServerMenuSettings.Entry> entries) {

    public static final ServerMenuSettings DISABLED = new ServerMenuSettings(false, List.of());

    /** Пункт меню: иконка, имя/лор (MiniMessage), команда от игрока, слот (-1 = авто-раскладка). */
    public record Entry(int slot, Material icon, String name, String lore, String command, String plugin, String permission) {
        public Entry(int slot, Material icon, String name, String lore, String command) {
            this(slot, icon, name, lore, command, "", "");
        }
        boolean available(java.util.function.Predicate<String> plugins, java.util.function.Predicate<String> permissions) {
            return (plugin.isBlank() || plugins.test(plugin)) && (permission.isBlank() || permissions.test(permission));
        }
    }

    public static ServerMenuSettings from(FileConfiguration config) {
        boolean enabled = config.getBoolean("server-menu.enabled", true);
        List<Map<?, ?>> maps = config.getMapList("server-menu.items");
        if (maps.isEmpty()) {
            return new ServerMenuSettings(enabled, List.of());
        }
        List<Entry> entries = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            if (map == null) continue;
            int slot = toInt(map.get("slot"), -1);
            Object iconObj = map.get("icon");
            String iconRaw = String.valueOf(iconObj == null ? "PAPER" : iconObj).trim().toUpperCase(java.util.Locale.ROOT);
            Material icon = Material.matchMaterial(iconRaw);
            if (icon == null || !icon.isItem()) {
                // неизвестная иконка → безопасный PAPER (сервер не должен падать из-за конфига)
                icon = Material.PAPER;
            }
            Object nameObj = map.get("name");
            Object loreObj = map.get("lore");
            Object commandObj = map.get("command");
            String name = String.valueOf(nameObj == null ? "" : nameObj).trim();
            String lore = String.valueOf(loreObj == null ? "" : loreObj).trim();
            String command = String.valueOf(commandObj == null ? "" : commandObj).trim();
            if (name.isEmpty() || command.isEmpty() || slot < -1 || slot > 53) {
                continue;
            }
            entries.add(new Entry(slot, icon, name, lore, command, string(map.get("plugin")), string(map.get("permission"))));
        }
        return new ServerMenuSettings(enabled, List.copyOf(entries));
    }

    private static String string(Object raw) { return raw == null ? "" : raw.toString().trim(); }

    private static int toInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}

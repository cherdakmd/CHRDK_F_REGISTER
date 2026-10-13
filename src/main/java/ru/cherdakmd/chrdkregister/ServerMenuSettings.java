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
    public record Entry(int slot, Material icon, String name, String lore, String command) {
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
            String iconRaw = String.valueOf(map.getOrDefault("icon", "PAPER")).trim().toUpperCase();
            Material icon = Material.matchMaterial(iconRaw);
            if (icon == null || !icon.isItem()) {
                // неизвестная иконка → безопасный PAPER (сервер не должен падать из-за конфига)
                icon = Material.PAPER;
            }
            String name = String.valueOf(map.getOrDefault("name", "")).trim();
            String lore = String.valueOf(map.getOrDefault("lore", "")).trim();
            String command = String.valueOf(map.getOrDefault("command", "")).trim();
            if (name.isEmpty() || command.isEmpty() || slot < -1 || slot > 53) {
                continue;
            }
            entries.add(new Entry(slot, icon, name, lore, command));
        }
        return new ServerMenuSettings(enabled, List.copyOf(entries));
    }

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

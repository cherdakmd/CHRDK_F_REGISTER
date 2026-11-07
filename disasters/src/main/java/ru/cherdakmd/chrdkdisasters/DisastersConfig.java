package ru.cherdakmd.chrdkdisasters;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Неизменяемый снимок настроек CHRDK_Disasters. Все числа зажаты в безопасных
 * границах: случайная опечатка в YAML не должна превратить катастрофу в очистку
 * мира. Главный принцип — <b>по умолчанию всё выключено</b>: владелец явно
 * включает модуль, миры и каждую катастрофу.
 */
public record DisastersConfig(
        boolean enabled,
        String locale,
        int tickSeconds,
        int minSeconds,
        int maxSeconds,
        int announceCooldownSeconds,
        int restoreAfterSeconds,
        int maxLevel,
        int radius,
        int maxBlocks,
        int spawnProtectionRadius,
        boolean respectClaims,
        Set<String> worlds,
        Map<DisasterKind, Boolean> kinds) {

    public static DisastersConfig parse(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("disasters.enabled", false);
        String locale = trim(cfg.getString("disasters.locale", "ru"));
        int tick = clamp(cfg.getInt("disasters.tick-seconds", 30), 5, 300);
        int min = clamp(cfg.getInt("disasters.min-seconds", 1800), 60, 86_400);
        int max = clamp(cfg.getInt("disasters.max-seconds", 5400), 60, 172_800);
        if (max < min) {
            max = min;
        }
        int announce = clamp(cfg.getInt("disasters.announce-cooldown-seconds", 600), 10, 86_400);
        // 0 = оставить изменения в мире навсегда (только по явному решению).
        int restore = clamp(cfg.getInt("disasters.restore-after-seconds", 600), 0, 86_400);
        int maxLevel = clamp(cfg.getInt("disasters.max-level", 5), 1, 5);
        int radius = clamp(cfg.getInt("disasters.radius", 12), 2, 48);
        int maxBlocks = clamp(cfg.getInt("disasters.max-blocks", 256), 1, 4096);
        int spawnRadius = clamp(cfg.getInt("disasters.spawn-protection-radius", 64), 0, 512);
        boolean claims = cfg.getBoolean("disasters.respect-claims", true);

        Set<String> worlds = new LinkedHashSet<>();
        for (String world : cfg.getStringList("disasters.worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.trim());
            }
        }
        Map<DisasterKind, Boolean> kinds = new EnumMap<>(DisasterKind.class);
        // Разрушительная площадная катастрофа по умолчанию всё равно выключена,
        // даже если владелец включил сам модуль.
        for (DisasterKind kind : DisasterKind.values()) {
            boolean stock = kind != DisasterKind.EARTHQUAKE;
            kinds.put(kind, cfg.getBoolean("disasters.kinds." + kind.id(), stock));
        }

        return new DisastersConfig(enabled, locale.isEmpty() ? "ru" : locale,
                tick, min, max, announce, restore, maxLevel, radius, maxBlocks,
                spawnRadius, claims, Set.copyOf(worlds), Map.copyOf(kinds));
    }

    /** Пустой список worlds означает все миры; пустое enabled-множество — тишина. */
    public boolean worldAllowed(String world) {
        return worlds.isEmpty() || worlds.contains(world);
    }

    public List<DisasterKind> enabledKinds() {
        List<DisasterKind> out = new ArrayList<>();
        for (DisasterKind kind : DisasterKind.values()) {
            if (Boolean.TRUE.equals(kinds.get(kind))) {
                out.add(kind);
            }
        }
        return List.copyOf(out);
    }

    public long minMillis() {
        return minSeconds * 1000L;
    }

    public long maxMillis() {
        return maxSeconds * 1000L;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}

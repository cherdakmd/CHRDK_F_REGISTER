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
 * включает модуль, миры и каждую разрушительную катастрофу.
 *
 * <p>Вторая итерация добавляет план фаз, звуки и мягкие эффекты состояния.
 * Они тоже выключаются одним ключом и не меняют лимиты блоков: активная фаза
 * лишь распределяет прежний {@code max-blocks} по нескольким тикам.</p>
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
        Map<DisasterKind, Boolean> kinds,
        DisasterPhasePlan phases,
        boolean soundsEnabled,
        boolean playerEffectsEnabled,
        float soundVolume,
        float soundPitch,
        Map<DisasterKind, String> sounds) {

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

        DisasterPhasePlan phases = DisasterPhasePlan.from(
                clamp(cfg.getInt("disasters.phases.warn-seconds", 5), 0, 60),
                clamp(cfg.getInt("disasters.phases.active-seconds", 20), 2, 300),
                clamp(cfg.getInt("disasters.phases.fade-seconds", 5), 0, 60),
                clamp(cfg.getInt("disasters.phases.tick-seconds", 2), 1, 10));

        boolean soundsEnabled = cfg.getBoolean("disasters.sounds.enabled", true);
        boolean effectsEnabled = cfg.getBoolean("disasters.player-effects.enabled", true);
        float volume = (float) cfg.getDouble("disasters.sounds.volume", 0.7);
        float pitch = (float) cfg.getDouble("disasters.sounds.pitch", 1.0);

        Set<String> worlds = new LinkedHashSet<>();
        for (String world : cfg.getStringList("disasters.worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.trim());
            }
        }
        Map<DisasterKind, Boolean> kinds = new EnumMap<>(DisasterKind.class);
        Map<DisasterKind, String> soundNames = new EnumMap<>(DisasterKind.class);
        for (DisasterKind kind : DisasterKind.values()) {
            kinds.put(kind, cfg.getBoolean("disasters.kinds." + kind.id(), kind.stockEnabled()));
            String sound = trimUpper(cfg.getString("disasters.sounds." + kind.id(), kind.sound()));
            soundNames.put(kind, sound.isEmpty() ? "NONE" : sound);
        }

        return new DisastersConfig(enabled, locale.isEmpty() ? "ru" : locale,
                tick, min, max, announce, restore, maxLevel, radius, maxBlocks,
                spawnRadius, claims, Set.copyOf(worlds), Map.copyOf(kinds), phases,
                soundsEnabled, effectsEnabled, volume, pitch, Map.copyOf(soundNames));
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

    /** Звук катастрофы с учётом настройки владельца. */
    public String soundFor(DisasterKind kind) {
        return soundsEnabled ? sounds.getOrDefault(kind, kind.sound()) : "NONE";
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

    private static String trimUpper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}

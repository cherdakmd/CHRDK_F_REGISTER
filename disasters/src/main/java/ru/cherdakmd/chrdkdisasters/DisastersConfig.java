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
 * мира или бесконечный поток алмазов. Главный принцип — <b>по умолчанию всё
 * выключено</b>: владелец явно включает модуль, миры и каждую разрушительную
 * катастрофу.
 *
 * <p>Третья итерация добавляет осколки, магазин, прокачку стойкости и мягкие
 * интеграции. Они не меняют лимиты блоков и не начисляют физический дроп.</p>
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
        int maxConcurrent,
        int spawnProtectionRadius,
        boolean respectClaims,
        Set<String> worlds,
        Map<DisasterKind, Boolean> kinds,
        DisasterPhasePlan phases,
        boolean soundsEnabled,
        boolean playerEffectsEnabled,
        float soundVolume,
        float soundPitch,
        Map<DisasterKind, String> sounds,
        DisasterSettings.Cataclysm cataclysm,
        DisasterSettings.Rewards rewards,
        DisasterSettings.Progression progression,
        DisasterSettings.Integrations integrations,
        DisasterSettings.Shop shop) {

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
        int maxConcurrent = clamp(cfg.getInt("disasters.max-concurrent", 8), 1, 64);
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

        DisasterSettings.Cataclysm cataclysm = new DisasterSettings.Cataclysm(
                cfg.getBoolean("disasters.cataclysm.enabled", false),
                cfg.getBoolean("disasters.cataclysm.titles", true),
                cfg.getBoolean("disasters.cataclysm.damage", true),
                cfg.getBoolean("disasters.cataclysm.knockback", true),
                clamp(cfg.getInt("disasters.cataclysm.max-fire-seconds", 4), 0, 30),
                clamp(cfg.getInt("disasters.cataclysm.max-affected-players", 24), 1, 128),
                clampDouble(cfg.getDouble("disasters.cataclysm.max-damage-per-pulse", 6.0), 0.0, 20.0));
        DisasterSettings.Rewards rewards = new DisasterSettings.Rewards(
                cfg.getBoolean("disasters.rewards.enabled", true),
                clamp(cfg.getInt("disasters.rewards.base-shards", 5), 0, 1000),
                clamp(cfg.getInt("disasters.rewards.per-level-shards", 2), 0, 100),
                clamp(cfg.getInt("disasters.rewards.daily-cap", 120), 0, 10_000),
                clampDouble(cfg.getDouble("disasters.rewards.bonus-cap", 1.5), 1.0, 3.0));
        DisasterSettings.Progression progression = new DisasterSettings.Progression(
                clamp(cfg.getInt("disasters.progression.base-xp", 100), 10, 10_000),
                clamp(cfg.getInt("disasters.progression.xp-per-survive", 30), 0, 1000),
                clamp(cfg.getInt("disasters.progression.xp-per-level", 8), 0, 200),
                clamp(cfg.getInt("disasters.progression.max-level", 10), 1, 50));
        String skillId = trim(cfg.getString("disasters.integrations.skill-id", "defense"));
        DisasterSettings.Integrations integrations = new DisasterSettings.Integrations(
                cfg.getBoolean("disasters.integrations.achievements", true),
                cfg.getBoolean("disasters.integrations.skills", true),
                skillId.isEmpty() ? "defense" : skillId,
                clamp(cfg.getInt("disasters.integrations.skills-units", 2), 1, 100));
        DisasterSettings.Shop shop = new DisasterSettings.Shop(
                clamp(cfg.getInt("disasters.shop.diamonds.price", 20), 1, 10_000),
                clamp(cfg.getInt("disasters.shop.diamonds.amount", 16), 1, 576),
                clamp(cfg.getInt("disasters.shop.supplies.price", 12), 1, 10_000),
                clamp(cfg.getInt("disasters.shop.supplies.diamonds", 4), 1, 64),
                clamp(cfg.getInt("disasters.shop.ward.price", 30), 1, 10_000),
                clamp(cfg.getInt("disasters.shop.ward.minutes", 30), 1, 1440));

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
                maxConcurrent, spawnRadius, claims, Set.copyOf(worlds), Map.copyOf(kinds), phases,
                soundsEnabled, effectsEnabled, volume, pitch, Map.copyOf(soundNames),
                cataclysm, rewards, progression, integrations, shop);
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

    private static double clampDouble(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimUpper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}

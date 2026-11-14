package ru.cherdakmd.chrdkmobs;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Настройки тиров мобов: границы, надбавки и награда. */
record MobsConfig(boolean enabled, List<Integer> thresholds,
                  double healthPerTier, double damagePerTier,
                  boolean namePrefix, long rewardPerTier) {

    static MobsConfig parse(FileConfiguration cfg, Logger log) {
        boolean enabled = cfg.getBoolean("general.enabled", true);
        List<Integer> raw = cfg.getIntegerList("tiers.distances");
        List<Integer> thresholds = new ArrayList<>();
        int previous = 0;
        for (int value : raw) {
            if (value <= previous || value <= 0) {
                log.warning("tiers.distances: границы должны строго расти и быть положительными; "
                        + value + " пропущена");
                continue;
            }
            if (thresholds.size() >= MobTierMath.MAX_THRESHOLDS) {
                log.warning("tiers.distances: больше " + MobTierMath.MAX_THRESHOLDS
                        + " границ не поддерживается; лишние пропущены");
                break;
            }
            thresholds.add(value);
            previous = value;
        }
        double healthPerTier = clampPercent(cfg.getDouble("tiers.health-per-tier", 0.5));
        double damagePerTier = clampPercent(cfg.getDouble("tiers.damage-per-tier", 0.25));
        boolean namePrefix = cfg.getBoolean("tiers.name-prefix", true);
        long rewardPerTier = Math.max(0L, cfg.getLong("tiers.reward-per-tier", 1L));
        return new MobsConfig(enabled, List.copyOf(thresholds), healthPerTier,
                damagePerTier, namePrefix, rewardPerTier);
    }

    private static double clampPercent(double value) {
        return Math.min(10.0, Math.max(0.0, Double.isFinite(value) ? value : 0.0));
    }

    String describe() {
        return String.format(Locale.ROOT,
                "enabled=%s, tiers=%d (%s..%s), health +%,.0f%%/тир, damage +%,.0f%%/тир, reward %d ⛁/тир",
                enabled, thresholds.size() + 1,
                thresholds.isEmpty() ? "1" : "2",
                thresholds.size() + 1,
                healthPerTier * 100, damagePerTier * 100, rewardPerTier);
    }
}

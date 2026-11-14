package ru.cherdakmd.chrdkmobs;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Настройки тиров мобов: границы, надбавки, награда, привязка к прокачке
 * и элитные мобы.
 */
record MobsConfig(boolean enabled, List<Integer> thresholds,
                  double healthPerTier, double damagePerTier,
                  boolean namePrefix, long rewardPerTier,
                  Progression progression, Elite elite) {

    /**
     * Привязка к прокачке: мощность моба закрепляется при появлении по
     * очкам прогресса ближайшего игрока (уровни навыков + престиж).
     */
    record Progression(boolean enabled, int radius,
                       double healthPer100, double damagePer100,
                       int prestigeWeight, double maxBonus) { }

    /** Элитные мобы: редкие усиленные враги со случайным набором способностей. */
    record Elite(double chance, double healthMultiplier, double damageMultiplier,
                 int abilitiesMin, int abilitiesMax, long rewardTiers,
                 List<String> epithets) { }

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
        Progression progression = new Progression(
                cfg.getBoolean("progression.enabled", true),
                (int) Math.min(1000, Math.max(16, cfg.getInt("progression.radius", 128))),
                clampPercent(cfg.getDouble("progression.health-per-100", 0.10)),
                clampPercent(cfg.getDouble("progression.damage-per-100", 0.05)),
                (int) Math.min(100, Math.max(0, cfg.getInt("progression.prestige-weight", 5))),
                clampPercent(cfg.getDouble("progression.max-bonus", 1.5)));
        List<String> epithets = new ArrayList<>();
        for (String epithet : cfg.getStringList("elite.epithets")) {
            if (!epithet.isBlank()) {
                epithets.add(epithet.trim());
            }
        }
        if (epithets.isEmpty()) {
            epithets = new ArrayList<>(List.of("Злобный", "Грозный", "Кровавый", "Проклятый",
                    "Бешеный", "Стальной", "Костоломный", "Безжалостный", "Тёмный", "Хищный",
                    "Голодный", "Зловещий"));
        }
        int abilitiesMin = (int) Math.min(EliteAbilities.values().length,
                Math.max(1, cfg.getInt("elite.abilities-min", 2)));
        int abilitiesMax = (int) Math.min(EliteAbilities.values().length,
                Math.max(abilitiesMin, cfg.getInt("elite.abilities-max", 3)));
        Elite elite = new Elite(
                Math.min(1.0, Math.max(0.0, cfg.getDouble("elite.chance", 0.03))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("elite.health-multiplier", 2.0))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("elite.damage-multiplier", 1.5))),
                abilitiesMin, abilitiesMax,
                Math.min(20L, Math.max(0L, cfg.getLong("elite.reward-tiers", 2L))),
                List.copyOf(epithets));
        return new MobsConfig(enabled, List.copyOf(thresholds), healthPerTier,
                damagePerTier, namePrefix, rewardPerTier, progression, elite);
    }

    private static double clampPercent(double value) {
        return Math.min(10.0, Math.max(0.0, Double.isFinite(value) ? value : 0.0));
    }

    String describe() {
        return String.format(Locale.ROOT,
                "enabled=%s, tiers=%d (%s..%s), health +%,.0f%%/тир, damage +%,.0f%%/тир, "
                        + "reward %d ⛁/тир, прогресс +%,.0f%%/100 очков (до +%,.0f%%), "
                        + "элита %,.1f%% (×%s здоровья, ×%s урона, %d..%d способностей)",
                enabled, thresholds.size() + 1,
                thresholds.isEmpty() ? "1" : "2",
                thresholds.size() + 1,
                healthPerTier * 100, damagePerTier * 100, rewardPerTier,
                progression.healthPer100() * 100, progression.maxBonus() * 100,
                elite.chance() * 100, elite.healthMultiplier(), elite.damageMultiplier(),
                elite.abilitiesMin(), elite.abilitiesMax());
    }
}

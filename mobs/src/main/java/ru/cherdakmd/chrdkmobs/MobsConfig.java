package ru.cherdakmd.chrdkmobs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Настройки тиров мобов: границы, свойства каждого тира, награда, лут,
 * привязка к прокачке, элитные мобы и редкие варианты.
 */
record MobsConfig(boolean enabled, List<Integer> thresholds, Map<Integer, Tier> table,
                  boolean namePrefix, boolean particles, Progression progression, Elite elite,
                  Variants variants, Loot loot, Mitigation mitigation) {

    /**
     * Свойства одного тира: множители здоровья и урона, число способностей
     * особых мобов (min..max), награда в {@code /bal}, число предметов лута
     * (min..max) и шанс дропа лута (0..1; сущности вариантов падают всегда).
     */
    record Tier(int tier, double health, double damage,
                int abilitiesMin, int abilitiesMax,
                long reward, int lootMin, int lootMax, double lootChance) { }

    /**
     * Привязка к прокачке: мощность моба закрепляется при появлении по
     * очкам прогресса ближайшего игрока (уровни навыков + престиж).
     */
    record Progression(boolean enabled, int radius,
                       double healthPer100, double damagePer100,
                       int prestigeWeight, double maxBonus) { }

    /** Элитные мобы: редкие усиленные враги со случайным набором способностей. */
    record Elite(double chance, double healthMultiplier, double damageMultiplier,
                 long rewardTiers, int lootBonus, List<String> epithets) { }

    /** Редкие варианты мобов (проклятые, тлеющие, всратые, кринжовые). */
    record Variants(double chance, double healthMultiplier, double damageMultiplier) { }

    /** Лут: опыт навыков за использование эссенций и сущностей вариантов. */
    record Loot(long essenceXp, long variantXp) { }

    /**
     * Потолок суммарного снижения входящего урона плагинами: мобы всегда
     * опасны. С тира 6 потолок падает на {@code perTierStep} за тир
     * (высшие тиры пробивают больше защиты), но не ниже {@code floor}.
     */
    record Mitigation(double cap, double perTierStep, double floor) { }

    /** Сколько всего тиров поддерживает таблица свойств. */
    public static final int MAX_TIERS = 20;

    /**
     * Свойства тира: строка таблицы, тиры выше последней строки наследуют её,
     * при пустой таблице — нейтральные значения.
     */
    Tier tier(int tier) {
        Tier row = table.get(Math.max(1, tier));
        if (row != null) {
            return row;
        }
        int last = table.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        return table.getOrDefault(last,
                new Tier(Math.max(1, tier), 1.0, 1.0, 1, 1, 0L, 1, 1, 0.35));
    }

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
        boolean namePrefix = cfg.getBoolean("tiers.name-prefix", true);
        Map<Integer, Tier> table = parseTable(cfg.getConfigurationSection("tiers.table"), log);
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
        Elite elite = new Elite(
                Math.min(1.0, Math.max(0.0, cfg.getDouble("elite.chance", 0.03))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("elite.health-multiplier", 2.0))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("elite.damage-multiplier", 1.5))),
                Math.min(20L, Math.max(0L, cfg.getLong("elite.reward-tiers", 2L))),
                (int) Math.min(20, Math.max(0, cfg.getInt("elite.loot-bonus", 1))),
                List.copyOf(epithets));
        Variants variants = new Variants(
                Math.min(1.0, Math.max(0.0, cfg.getDouble("variants.chance", 0.012))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("variants.health-multiplier", 2.5))),
                Math.min(10.0, Math.max(1.0, cfg.getDouble("variants.damage-multiplier", 1.75))));
        Loot loot = new Loot(
                Math.min(1_000_000L, Math.max(0L, cfg.getLong("loot.essence-xp", 40L))),
                Math.min(1_000_000L, Math.max(0L, cfg.getLong("loot.variant-xp", 60L))));
        boolean particles = cfg.getBoolean("effects.particles", true);
        Mitigation mitigation = new Mitigation(
                Math.min(1.0, Math.max(0.0, cfg.getDouble("mitigation.cap", 0.5))),
                Math.min(1.0, Math.max(0.0, cfg.getDouble("mitigation.per-tier-step", 0.04))),
                Math.min(1.0, Math.max(0.0, cfg.getDouble("mitigation.floor", 0.3))));
        return new MobsConfig(enabled, List.copyOf(thresholds), table, namePrefix, particles,
                progression, elite, variants, loot, mitigation);
    }

    /** Таблица свойств тиров 1..N из конфига; пустая или кривая строка — нейтральные значения. */
    private static Map<Integer, Tier> parseTable(ConfigurationSection section, Logger log) {
        Map<Integer, Tier> table = new LinkedHashMap<>();
        if (section == null) {
            return table;
        }
        List<Integer> keys = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                keys.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                log.warning("tiers.table: ключ '" + key + "' не номер тира; пропущен");
            }
        }
        keys.sort(Integer::compareTo);
        for (int tier : keys) {
            if (tier < 1 || tier > MAX_TIERS) {
                log.warning("tiers.table: тир " + tier + " вне 1.." + MAX_TIERS + "; пропущен");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(String.valueOf(tier));
            if (row == null) {
                continue;
            }
            List<Integer> abilities = row.getIntegerList("abilities");
            List<Integer> lootRange = row.getIntegerList("loot");
            table.put(tier, new Tier(tier,
                    Math.min(20.0, Math.max(0.1, row.getDouble("health", 1.0))),
                    Math.min(20.0, Math.max(0.1, row.getDouble("damage", 1.0))),
                    clampCount(abilities.isEmpty() ? 1 : abilities.get(0)),
                    clampCount(abilities.size() < 2 ? 2 : abilities.get(1)),
                    Math.max(0L, row.getLong("reward", 0L)),
                    clampCount(lootRange.isEmpty() ? 1 : lootRange.get(0)),
                    clampCount(lootRange.size() < 2 ? 1 : lootRange.get(1)),
                    Math.min(1.0, Math.max(0.0, row.getDouble("loot-chance", 0.35)))));
        }
        return table;
    }

    private static int clampCount(int value) {
        return Math.min(64, Math.max(1, value));
    }

    private static double clampPercent(double value) {
        return Math.min(10.0, Math.max(0.0, Double.isFinite(value) ? value : 0.0));
    }

    String describe() {
        return String.format(Locale.ROOT,
                "enabled=%s, tiers=%d (%s..%s), таблица %d тиров, прогресс +%,.0f%%/100 очков "
                        + "(до +%,.0f%%), элита %,.1f%%, варианты %,.1f%%, лут %d/%d опыта, "
                        + "потолок защиты %,.0f%%, частицы %s",
                enabled, thresholds.size() + 1,
                thresholds.isEmpty() ? "?" : thresholds.get(0),
                thresholds.isEmpty() ? "?" : thresholds.get(thresholds.size() - 1),
                table.size(),
                progression.healthPer100() * 100, progression.maxBonus() * 100,
                elite.chance() * 100, variants.chance() * 100,
                loot.essenceXp(), loot.variantXp(),
                mitigation.cap() * 100,
                particles ? "вкл" : "выкл");
    }
}

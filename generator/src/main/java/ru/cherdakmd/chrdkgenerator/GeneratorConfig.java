package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Конфигурация генератора (секция {@code generator:} config.yml).
 *
 * <p>Читается целиком и строго: пустая форма крафта, неизвестный материал,
 * не тот размер таблицы тиров — ошибка загрузки с понятным текстом, а не
 * тихий дефолт. Числа и таблицы после разбора недостижимых диапазонов не
 * бывают: их держит валидация.</p>
 */
public final class GeneratorConfig {

    /** Тиров ровно столько, сколько ожидает хранилище (1…{@value GeneratorStore#MAX_TIER}). */
    public static final int TIERS = GeneratorStore.MAX_TIER;

    /** Рецепт ядра на верстаке: форма 3×3 и ингредиенты-материалы. */
    public record Craft(boolean enabled, List<String> shape, Map<Character, String> ingredients) {
    }

    /** Один тир: название, темп производства и таблица продукции. */
    public record Tier(String name, int unitsPerCycle, List<GeneratorDrops.Drop> drops) {
    }

    private final int cycleSeconds;
    private final int bunkerBase;
    private final int offlineHours;
    private final double offlineEfficiency;
    private final boolean fuelEnabled;
    private final int cyclesPerBucket;
    private final double rebirthPercentPerRebirth;
    private final double rebirthDecay;
    private final double rebirthBonusCapPercent;
    private final Craft craft;
    private final List<Tier> tiers;

    private GeneratorConfig(int cycleSeconds, int bunkerBase, int offlineHours,
                            double offlineEfficiency, boolean fuelEnabled, int cyclesPerBucket,
                            double rebirthPercentPerRebirth, double rebirthDecay,
                            double rebirthBonusCapPercent, Craft craft, List<Tier> tiers) {
        this.cycleSeconds = cycleSeconds;
        this.bunkerBase = bunkerBase;
        this.offlineHours = offlineHours;
        this.offlineEfficiency = offlineEfficiency;
        this.fuelEnabled = fuelEnabled;
        this.cyclesPerBucket = cyclesPerBucket;
        this.rebirthPercentPerRebirth = rebirthPercentPerRebirth;
        this.rebirthDecay = rebirthDecay;
        this.rebirthBonusCapPercent = rebirthBonusCapPercent;
        this.craft = craft;
        this.tiers = List.copyOf(tiers);
    }

    /** Базовый цикл производства, секунды (модуль «Скорость» короче). */
    public int cycleSeconds() {
        return cycleSeconds;
    }

    /** Базовая ёмкость бункера, единиц; потолок = ёмкость × offline-hours. */
    public int bunkerBase() {
        return bunkerBase;
    }

    public int offlineHours() {
        return offlineHours;
    }

    public double offlineEfficiency() {
        return offlineEfficiency;
    }

    public boolean fuelEnabled() {
        return fuelEnabled;
    }

    public int cyclesPerBucket() {
        return cyclesPerBucket;
    }

    public double rebirthPercentPerRebirth() {
        return rebirthPercentPerRebirth;
    }

    public double rebirthDecay() {
        return rebirthDecay;
    }

    public double rebirthBonusCapPercent() {
        return rebirthBonusCapPercent;
    }

    public Craft craft() {
        return craft;
    }

    /** Семь тиров по порядку (индекс 0 — тир 1). */
    public List<Tier> tiers() {
        return tiers;
    }

    /** Тир по номеру 1…7. */
    public Tier tier(int tier) {
        return tiers.get(tier - 1);
    }

    /** Читает секцию {@code generator:}; любая пустота или мусор — IllegalArgumentException. */
    public static GeneratorConfig load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("generator");
        if (root == null) {
            throw new IllegalArgumentException("нет секции generator в config.yml");
        }
        int cycleSeconds = positive(root, "cycle-seconds");
        int bunkerBase = positive(root, "bunker-base");
        int offlineHours = positive(root, "offline-hours");
        double offlineEfficiency = root.getDouble("offline-efficiency", -1);
        if (offlineEfficiency < 0 || offlineEfficiency > 1) {
            throw new IllegalArgumentException("generator.offline-efficiency: ждём 0.0–1.0");
        }
        boolean fuelEnabled = root.getBoolean("fuel.enabled", false);
        int cyclesPerBucket = positive(root, "fuel.cycles-per-bucket");
        double percentPerRebirth = fraction(root, "rebirth-percent-per-rebirth", 0, Double.MAX_VALUE);
        double decay = fraction(root, "rebirth-decay", 0, 1);
        double bonusCap = fraction(root, "rebirth-bonus-cap-percent", 0, Double.MAX_VALUE);
        Craft craft = parseCraft(root.getConfigurationSection("craft"));
        List<Tier> tiers = parseTiers(root.getMapList("tiers"));
        return new GeneratorConfig(cycleSeconds, bunkerBase, offlineHours,
                offlineEfficiency, fuelEnabled, cyclesPerBucket,
                percentPerRebirth, decay, bonusCap, craft, tiers);
    }

    private static double fraction(ConfigurationSection root, String path, double min, double max) {
        double value = root.getDouble(path, Double.NaN);
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException("generator." + path + ": ждём число в " + min + ".." + max);
        }
        return value;
    }

    private static int positive(ConfigurationSection root, String path) {
        int value = root.getInt(path, -1);
        if (value <= 0) {
            throw new IllegalArgumentException("generator." + path + ": ждём положительное число");
        }
        return value;
    }

    private static Craft parseCraft(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("нет секции generator.craft");
        }
        boolean enabled = section.getBoolean("enabled", true);
        List<String> shape = section.getStringList("shape");
        if (shape.size() != 3) {
            throw new IllegalArgumentException("generator.craft.shape: ждём 3 строки");
        }
        Map<Character, String> ingredients = new LinkedHashMap<>();
        ConfigurationSection map = section.getConfigurationSection("ingredients");
        if (map == null) {
            throw new IllegalArgumentException("нет секции generator.craft.ingredients");
        }
        for (String key : map.getKeys(false)) {
            if (key.length() != 1) {
                throw new IllegalArgumentException("generator.craft.ingredients: ключ — один символ: " + key);
            }
            ingredients.put(key.charAt(0), material(map.getString(key)));
        }
        for (String row : shape) {
            if (row.isEmpty() || row.length() > 3) {
                throw new IllegalArgumentException("generator.craft.shape: строка 1–3 символа: " + row);
            }
            for (char symbol : row.toCharArray()) {
                if (!ingredients.containsKey(symbol)) {
                    throw new IllegalArgumentException("generator.craft: нет ингредиента для '" + symbol + "'");
                }
            }
        }
        for (char symbol : ingredients.keySet()) {
            boolean used = shape.stream().anyMatch(row -> row.indexOf(symbol) >= 0);
            if (!used) {
                throw new IllegalArgumentException("generator.craft: лишний ингредиент '" + symbol + "'");
            }
        }
        return new Craft(enabled, List.copyOf(shape), Map.copyOf(ingredients));
    }

    private static List<Tier> parseTiers(List<?> rows) {
        if (rows.size() != TIERS) {
            throw new IllegalArgumentException("generator.tiers: ждём ровно " + TIERS + " тиров");
        }
        List<Tier> tiers = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("generator.tiers[" + i + "]: ждём карту");
            }
            String name = string(row.get("name"));
            if (name.isEmpty()) {
                throw new IllegalArgumentException("generator.tiers[" + i + "].name: пусто");
            }
            int unitsPerCycle = integer(row.get("units-per-cycle"), "tiers[" + i + "].units-per-cycle");
            if (unitsPerCycle <= 0) {
                throw new IllegalArgumentException("generator.tiers[" + i + "].units-per-cycle: ждём > 0");
            }
            if (!(row.get("drops") instanceof List<?> dropRows) || dropRows.isEmpty()) {
                throw new IllegalArgumentException("generator.tiers[" + i + "].drops: пустая таблица");
            }
            List<GeneratorDrops.Drop> drops = new ArrayList<>();
            for (int j = 0; j < dropRows.size(); j++) {
                if (!(dropRows.get(j) instanceof Map<?, ?> drop)) {
                    throw new IllegalArgumentException("generator.tiers[" + i + "].drops[" + j + "]: ждём карту");
                }
                String material = material(drop.get("material"));
                int weight = integer(drop.get("weight"), "tiers[" + i + "].drops[" + j + "].weight");
                if (weight <= 0) {
                    throw new IllegalArgumentException("generator.tiers[" + i + "].drops[" + j + "].weight: ждём > 0");
                }
                boolean rare = Boolean.TRUE.equals(drop.get("rare"));
                drops.add(new GeneratorDrops.Drop(material, weight, rare));
            }
            tiers.add(new Tier(name, unitsPerCycle, List.copyOf(drops)));
        }
        return tiers;
    }

    private static String material(Object raw) {
        String name = string(raw).toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z0-9_]+") || Material.matchMaterial(name) == null) {
            throw new IllegalArgumentException("generator: неизвестный материал: " + raw);
        }
        return name;
    }

    private static int integer(Object raw, String path) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("generator." + path + ": ждём число");
    }

    private static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}

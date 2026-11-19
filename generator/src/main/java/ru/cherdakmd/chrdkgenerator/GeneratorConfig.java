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

    /** Один тир: название, темп производства, денежная порция и таблица продукции. */
    public record Tier(String name, int unitsPerCycle, GeneratorDrops.Money money,
                       List<GeneratorDrops.Drop> drops) {
    }

    /** Позиция цены улучшения: материал и количество. */
    public record Resource(String material, int amount) {
    }

    /** Цена улучшения: деньги (сжигаются) и ресурсы из инвентаря. */
    public record Cost(long money, List<Resource> resources) {
        public Cost {
            resources = List.copyOf(resources);
        }
    }

    private final int cycleSeconds;
    private final int bunkerBase;
    private final int offlineHours;
    private final double offlineEfficiency;
    private final int menuRange;
    private final int bunkerNotifyCooldownMinutes;
    private final int slotsRebirthUnlockAt;
    private final int slotsPaidRankMinStep;
    private final boolean fuelEnabled;
    private final int cyclesPerBucket;
    private final double rebirthPercentPerRebirth;
    private final double rebirthDecay;
    private final double rebirthBonusCapPercent;
    private final int payoutBase;
    private final int payoutPerTier;
    private final int payoutPerRankStep;
    private final int payoutHardMax;
    private final Craft craft;
    private final List<Tier> tiers;
    private final List<Cost> tierCosts;
    private final List<Cost> moduleCosts;
    private final Cost chunkCost;

    private GeneratorConfig(int cycleSeconds, int bunkerBase, int offlineHours,
                            double offlineEfficiency, int menuRange,
                            int bunkerNotifyCooldownMinutes,
                            int slotsRebirthUnlockAt, int slotsPaidRankMinStep,
                            boolean fuelEnabled, int cyclesPerBucket,
                            double rebirthPercentPerRebirth, double rebirthDecay,
                            double rebirthBonusCapPercent,
                            int payoutBase, int payoutPerTier, int payoutPerRankStep, int payoutHardMax,
                            Craft craft, List<Tier> tiers,
                            List<Cost> tierCosts, List<Cost> moduleCosts, Cost chunkCost) {
        this.cycleSeconds = cycleSeconds;
        this.bunkerBase = bunkerBase;
        this.offlineHours = offlineHours;
        this.offlineEfficiency = offlineEfficiency;
        this.menuRange = menuRange;
        this.bunkerNotifyCooldownMinutes = bunkerNotifyCooldownMinutes;
        this.slotsRebirthUnlockAt = slotsRebirthUnlockAt;
        this.slotsPaidRankMinStep = slotsPaidRankMinStep;
        this.fuelEnabled = fuelEnabled;
        this.cyclesPerBucket = cyclesPerBucket;
        this.rebirthPercentPerRebirth = rebirthPercentPerRebirth;
        this.rebirthDecay = rebirthDecay;
        this.rebirthBonusCapPercent = rebirthBonusCapPercent;
        this.payoutBase = payoutBase;
        this.payoutPerTier = payoutPerTier;
        this.payoutPerRankStep = payoutPerRankStep;
        this.payoutHardMax = payoutHardMax;
        this.craft = craft;
        this.tiers = List.copyOf(tiers);
        this.tierCosts = List.copyOf(tierCosts);
        this.moduleCosts = List.copyOf(moduleCosts);
        this.chunkCost = chunkCost;
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

    /** Радиус доступа к управлению генератором от ядра, блоков (чанк ядра доступен всегда). */
    public int menuRange() {
        return menuRange;
    }

    /** Кулдаун живого оповещения о полном бункере, минуты; 0 — выключено. */
    public int bunkerNotifyCooldownMinutes() {
        return bunkerNotifyCooldownMinutes;
    }

    /** Перерождений для второго слота генераторов; 0 — слот выключен. */
    public int slotsRebirthUnlockAt() {
        return slotsRebirthUnlockAt;
    }

    /** Ступень платного ранга для третьего слота; 0 — слот выключен. */
    public int slotsPaidRankMinStep() {
        return slotsPaidRankMinStep;
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

    public int payoutBase() {
        return payoutBase;
    }

    public int payoutPerTier() {
        return payoutPerTier;
    }

    public int payoutPerRankStep() {
        return payoutPerRankStep;
    }

    public int payoutHardMax() {
        return payoutHardMax;
    }

    public Craft craft() {
        return craft;
    }

    /** Семь тиров по порядку (индекс 0 — тир 1). */
    public List<Tier> tiers() {
        return tiers;
    }

    /** Тир по номеру 1…9. */
    public Tier tier(int tier) {
        return tiers.get(tier - 1);
    }

    /** Цены шагов тира (индекс 0 — переход 1→2). */
    public List<Cost> tierCosts() {
        return tierCosts;
    }

    /** Цены уровней модуля (индекс 0 — покупка уровня 1). */
    public List<Cost> moduleCosts() {
        return moduleCosts;
    }

    /** Цена модуля «Чанк» (разовая). */
    public Cost chunkCost() {
        return chunkCost;
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
        int menuRange = root.getInt("menu-range", 16);
        if (menuRange < 1 || menuRange > 64) {
            throw new IllegalArgumentException("generator.menu-range: ждём 1…64");
        }
        int bunkerNotifyCooldownMinutes = root.getInt("bunker-notify-cooldown-minutes", 30);
        if (bunkerNotifyCooldownMinutes < 0 || bunkerNotifyCooldownMinutes > 1440) {
            throw new IllegalArgumentException("generator.bunker-notify-cooldown-minutes: ждём 0…1440");
        }
        int slotsRebirthUnlockAt = root.getInt("slots.rebirth-unlock-at", 1);
        if (slotsRebirthUnlockAt < 0 || slotsRebirthUnlockAt > 1_000_000) {
            throw new IllegalArgumentException("generator.slots.rebirth-unlock-at: ждём 0…1000000");
        }
        int slotsPaidRankMinStep = root.getInt("slots.paid-rank-min-step", 1);
        if (slotsPaidRankMinStep < 0 || slotsPaidRankMinStep > 5) {
            throw new IllegalArgumentException("generator.slots.paid-rank-min-step: ждём 0…5");
        }
        boolean fuelEnabled = root.getBoolean("fuel.enabled", false);
        int cyclesPerBucket = positive(root, "fuel.cycles-per-bucket");
        double percentPerRebirth = fraction(root, "rebirth-percent-per-rebirth", 0, Double.MAX_VALUE);
        double decay = fraction(root, "rebirth-decay", 0, 1);
        double bonusCap = fraction(root, "rebirth-bonus-cap-percent", 0, Double.MAX_VALUE);
        ConfigurationSection payouts = root.getConfigurationSection("payouts");
        if (payouts == null) {
            throw new IllegalArgumentException("нет секции generator.payouts");
        }
        int payoutBase = positive(payouts, "daily-cap-base");
        int payoutPerTier = positive(payouts, "daily-cap-per-tier");
        int payoutPerRankStep = positive(payouts, "daily-cap-per-rank-step");
        int payoutHardMax = positive(payouts, "daily-cap-hard-max");
        Craft craft = parseCraft(root.getConfigurationSection("craft"));
        List<Tier> tiers = parseTiers(root.getMapList("tiers"));
        ConfigurationSection upgrades = root.getConfigurationSection("upgrades");
        if (upgrades == null) {
            throw new IllegalArgumentException("нет секции generator.upgrades");
        }
        List<Cost> tierCosts = parseCosts(upgrades.getMapList("tiers"), "upgrades.tiers", TIERS - 1);
        List<Cost> moduleCosts = parseCosts(upgrades.getMapList("modules"), "upgrades.modules",
                GeneratorStore.MAX_LEVEL);
        Cost chunkCost = parseCost(upgrades.get("chunk"), "upgrades.chunk");
        return new GeneratorConfig(cycleSeconds, bunkerBase, offlineHours,
                offlineEfficiency, menuRange, bunkerNotifyCooldownMinutes,
                slotsRebirthUnlockAt, slotsPaidRankMinStep,
                fuelEnabled, cyclesPerBucket,
                percentPerRebirth, decay, bonusCap,
                payoutBase, payoutPerTier, payoutPerRankStep, payoutHardMax,
                craft, tiers, tierCosts, moduleCosts, chunkCost);
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
            GeneratorDrops.Money money = null;
            if (row.get("money") != null) {
                Map<?, ?> moneyRow = asMap(row.get("money"));
                if (moneyRow == null) {
                    throw new IllegalArgumentException("generator.tiers[" + i + "].money: ждём карту");
                }
                Object rawChance = moneyRow.get("chance");
                double chance = rawChance instanceof Number number
                        ? number.doubleValue() : Double.NaN;
                if (Double.isNaN(chance) || chance <= 0 || chance > 1) {
                    throw new IllegalArgumentException("generator.tiers[" + i + "].money.chance: ждём 0..1");
                }
                int portion = integer(moneyRow.get("portion"), "tiers[" + i + "].money.portion");
                if (portion <= 0) {
                    throw new IllegalArgumentException("generator.tiers[" + i + "].money.portion: ждём > 0");
                }
                money = new GeneratorDrops.Money(chance, portion);
            }
            if (!(row.get("drops") instanceof List<?> dropRows) || dropRows.isEmpty()) {
                throw new IllegalArgumentException("generator.tiers[" + i + "].drops: пустая таблица");
            }
            List<GeneratorDrops.Drop> drops = new ArrayList<>();
            for (int j = 0; j < dropRows.size(); j++) {
                Map<?, ?> drop = asMap(dropRows.get(j));
                if (drop == null) {
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
            tiers.add(new Tier(name, unitsPerCycle, money, List.copyOf(drops)));
        }
        return tiers;
    }

    private static List<Cost> parseCosts(List<?> rows, String path, int expected) {
        if (rows.size() != expected) {
            throw new IllegalArgumentException("generator." + path + ": ждём ровно " + expected + " цен");
        }
        List<Cost> costs = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            costs.add(parseCost(rows.get(i), path + "[" + i + "]"));
        }
        return costs;
    }

    private static Cost parseCost(Object raw, String path) {
        Map<?, ?> row = asMap(raw);
        if (row == null) {
            throw new IllegalArgumentException("generator." + path + ": ждём карту");
        }
        Object rawMoney = row.get("money");
        long money = rawMoney == null ? 0 : integer(rawMoney, path + ".money");
        if (money < 0 || money > 1_000_000) {
            throw new IllegalArgumentException("generator." + path + ".money: ждём 0…1000000");
        }
        List<Resource> resources = new ArrayList<>();
        if (row.get("resources") != null) {
            if (!(row.get("resources") instanceof List<?> resourceRows)) {
                throw new IllegalArgumentException("generator." + path + ".resources: ждём список");
            }
            for (int i = 0; i < resourceRows.size(); i++) {
                Map<?, ?> resource = asMap(resourceRows.get(i));
                if (resource == null) {
                    throw new IllegalArgumentException("generator." + path + ".resources[" + i + "]: ждём карту");
                }
                String material = material(resource.get("material"));
                int amount = integer(resource.get("amount"), path + ".resources[" + i + "].amount");
                if (amount <= 0 || amount > 64 * 36) {
                    throw new IllegalArgumentException("generator." + path + ".resources[" + i + "].amount: ждём 1…2304");
                }
                resources.add(new Resource(material, amount));
            }
        }
        if (money <= 0 && resources.isEmpty()) {
            throw new IllegalArgumentException("generator." + path + ": цена пуста");
        }
        return new Cost(money, resources);
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

    /** Bukkit отдаёт вложенные секции как MemorySection — приводим к карте значений. */
    private static Map<?, ?> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        if (raw instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        return null;
    }
}

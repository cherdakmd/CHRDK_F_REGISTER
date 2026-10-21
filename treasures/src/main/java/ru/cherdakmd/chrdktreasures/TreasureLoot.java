package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Ванильная часть клада: лут-таблицы по редкости и региону.
 *
 * <p>Таблицы живут в {@code loot.yml}: у каждой редкости есть общий пул, а у
 * каждого региона — свой пул на каждую редкость. Бросок смешивает общий и
 * региональный пулы, причём региональные записи весят вдвое больше: клад
 * должен пахнуть местом, где он найден, а не быть абстрактным сундуком.</p>
 *
 * <p>У записи есть порог {@code unlock}: пока игрок не добыл столько блоков,
 * запись не участвует в броске. Так дорогие вещи (незерит, звезда, элитры)
 * не выпадают новичку из первого камня, даже если ему улыбнулась редкость.</p>
 *
 * <p>Класс чистый: материалы хранятся именами, случай приходит снаружи —
 * количества, веса и пороги проверяются тестами без сервера.</p>
 */
public final class TreasureLoot {

    /** Имя файла с таблицами в папке плагина. */
    public static final String FILE_NAME = "loot.yml";

    /**
     * Запись пула: материал, диапазон количества, вес, порог прогресса и,
     * если нужно, особое имя предмета.
     *
     * <p>Имя — необязательное: «Кровавый суп» и «Зависть пиглина» остаются
     * обычными предметами, но приходят с собственным названием, как в
     * оригинальных таблицах. Пустое имя — ванильный предмет.</p>
     */
    public record LootEntry(String material, int min, int max, int weight, int unlock,
                            String name) {

        /** Запись без порога — удобно для встроенного набора и тестов. */
        public LootEntry(String material, int min, int max, int weight) {
            this(material, min, max, weight, 0, "");
        }

        /** Запись с порогом, но без особого имени. */
        public LootEntry(String material, int min, int max, int weight, int unlock) {
            this(material, min, max, weight, unlock, "");
        }

        public LootEntry {
            if (name == null) {
                name = "";
            }
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("запись добычи без материала");
            }
            if (min < 1 || max < min) {
                throw new IllegalArgumentException("кривой диапазон у " + material);
            }
            if (weight < 1) {
                throw new IllegalArgumentException("вес меньше единицы у " + material);
            }
            if (unlock < 0) {
                throw new IllegalArgumentException("отрицательный порог у " + material);
            }
        }
    }

    /** Готовый стек добычи; {@code name} пустой у ванильных предметов. */
    public record LootStack(String material, int amount, String name) {

        public LootStack(String material, int amount) {
            this(material, amount, "");
        }

        public LootStack {
            if (name == null) {
                name = "";
            }
        }

        /** Ключ агрегации: одинаковые материалы с разными именами — разные стеки. */
        String key() {
            return name.isBlank() ? material : material + '\u0000' + name;
        }
    }

    /** Набор таблиц: общие пулы и региональные пулы по редкостям. */
    public static final class Table {

        private final Map<TreasureTier, List<LootEntry>> global =
                new EnumMap<>(TreasureTier.class);
        private final Map<TreasureRegion, Map<TreasureTier, List<LootEntry>>> regional =
                new EnumMap<>(TreasureRegion.class);
        private final int size;

        public Table() {
            for (TreasureTier tier : TreasureTier.values()) {
                global.put(tier, List.of());
            }
            for (TreasureRegion region : TreasureRegion.values()) {
                Map<TreasureTier, List<LootEntry>> tiers = new EnumMap<>(TreasureTier.class);
                for (TreasureTier tier : TreasureTier.values()) {
                    tiers.put(tier, List.of());
                }
                regional.put(region, tiers);
            }
            this.size = 0;
        }

        private Table(Map<TreasureTier, List<LootEntry>> global,
                      Map<TreasureRegion, Map<TreasureTier, List<LootEntry>>> regional) {
            this.global.putAll(global);
            this.regional.putAll(regional);
            int count = 0;
            for (List<LootEntry> entries : global.values()) {
                count += entries.size();
            }
            for (Map<TreasureTier, List<LootEntry>> tiers : regional.values()) {
                for (List<LootEntry> entries : tiers.values()) {
                    count += entries.size();
                }
            }
            this.size = count;
        }

        /** Общий пул редкости. */
        public List<LootEntry> global(TreasureTier tier) {
            return global.getOrDefault(tier, List.of());
        }

        /** Региональный пул редкости. */
        public List<LootEntry> regional(TreasureRegion region, TreasureTier tier) {
            Map<TreasureTier, List<LootEntry>> tiers = regional.get(region);
            return tiers == null ? List.of() : tiers.getOrDefault(tier, List.of());
        }

        /** Все региональные записи региона независимо от редкости. */
        public List<LootEntry> regional(TreasureRegion region) {
            List<LootEntry> out = new ArrayList<>();
            Map<TreasureTier, List<LootEntry>> tiers = regional.get(region);
            if (tiers != null) {
                for (List<LootEntry> entries : tiers.values()) {
                    out.addAll(entries);
                }
            }
            return List.copyOf(out);
        }

        /**
         * Смешанный пул: общий + региональный с двойным весом.
         * Возвращается новый список — исходные таблицы не меняются.
         */
        public List<LootEntry> combined(TreasureTier tier, TreasureRegion region) {
            List<LootEntry> out = new ArrayList<>(global(tier));
            for (LootEntry entry : regional(region, tier)) {
                out.add(new LootEntry(entry.material(), entry.min(), entry.max(),
                        entry.weight() * 2, entry.unlock(), entry.name()));
            }
            return List.copyOf(out);
        }

        /** Сколько всего записей в таблицах. */
        public int size() {
            return size;
        }
    }

    private static final Table BUILTIN = builtin();
    private static volatile Table active = shipped();

    private TreasureLoot() {
    }

    /* ------------------------------- управление ------------------------------- */

    /** Действующая таблица (встроенная, если loot.yml не загрузился). */
    public static Table active() {
        return active;
    }

    /** Устанавливает таблицы (плагин при старте и reload). */
    public static void install(Table table) {
        active = table == null || table.size() == 0 ? BUILTIN : table;
    }

    /** Встроенный набор — страховка, если loot.yml потерян или пуст. */
    public static Table builtin() {
        return BUILTIN;
    }

    /** Таблица из ресурса JAR: используется, пока плагин не подставил свою. */
    private static Table shipped() {
        try (InputStream stream = TreasureLoot.class.getResourceAsStream("/" + FILE_NAME)) {
            if (stream == null) {
                return BUILTIN;
            }
            Table table = load(stream, null);
            return table.size() == 0 ? BUILTIN : table;
        } catch (IOException | RuntimeException exception) {
            return BUILTIN;
        }
    }

    /**
     * Читает {@code loot.yml} с диска.
     *
     * <p>Битые записи не роняют плагин: строка пропускается с предупреждением,
     * остальные таблицы продолжают работать. Пустой или нечитаемый файл даёт
     * пустую таблицу, а {@link #install(Table)} подменяет её встроенным
     * набором: «кладов без добычи» не бывает.</p>
     */
    public static Table load(Path file, Logger logger) {
        if (file == null || !Files.isRegularFile(file)) {
            return BUILTIN;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(YamlConfiguration.loadConfiguration(reader), logger);
        } catch (IOException | RuntimeException exception) {
            if (logger != null) {
                logger.severe("[CHRDK_Treasures] Не удалось прочитать " + file + ": "
                        + exception.getMessage());
            }
            return BUILTIN;
        }
    }

    /** Читает таблицы из потока (ресурс JAR). */
    public static Table load(InputStream stream, Logger logger) throws IOException {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return load(YamlConfiguration.loadConfiguration(reader), logger);
        }
    }

    /** Разбор уже прочитанного YAML — удобно для тестов и внешних загрузчиков. */
    public static Table parse(YamlConfiguration yaml, Logger logger) {
        Map<TreasureTier, List<LootEntry>> global = new EnumMap<>(TreasureTier.class);
        Map<TreasureRegion, Map<TreasureTier, List<LootEntry>>> regional =
                new EnumMap<>(TreasureRegion.class);
        ConfigurationSection globalSection = yaml.getConfigurationSection("global");
        for (TreasureTier tier : TreasureTier.values()) {
            global.put(tier, readEntries(globalSection, tier.id(), "global." + tier.id(),
                    logger));
        }
        ConfigurationSection regionsSection = yaml.getConfigurationSection("regions");
        for (TreasureRegion region : TreasureRegion.values()) {
            Map<TreasureTier, List<LootEntry>> tiers = new EnumMap<>(TreasureTier.class);
            ConfigurationSection regionSection = regionsSection == null ? null
                    : regionsSection.getConfigurationSection(region.id());
            for (TreasureTier tier : TreasureTier.values()) {
                tiers.put(tier, readEntries(regionSection, tier.id(),
                        region.id() + "." + tier.id(), logger));
            }
            regional.put(region, tiers);
        }
        return new Table(global, regional);
    }

    private static List<LootEntry> readEntries(ConfigurationSection section, String key,
                                               String where, Logger logger) {
        if (section == null) {
            return List.of();
        }
        List<Map<?, ?>> rows = section.getMapList(key);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<LootEntry> out = new ArrayList<>(rows.size());
        for (Map<?, ?> row : rows) {
            String material = string(row.get("material"));
            int min = number(row.get("min"), 1);
            int max = number(row.get("max"), min);
            int weight = number(row.get("weight"), 1);
            int unlock = number(row.get("unlock"), 0);
            String name = string(row.get("name"));
            if (material.isBlank()) {
                warn(logger, where + ": запись без material — пропускаю");
                continue;
            }
            String normalized = material.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                // Проверяем имя сразу: опечатка в loot.yml не должна дожить до выдачи.
                org.bukkit.Material.valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                warn(logger, where + ": неизвестный материал «" + material + "» — пропускаю");
                continue;
            }
            try {
                out.add(new LootEntry(normalized, min, max, weight, unlock, name));
            } catch (IllegalArgumentException exception) {
                warn(logger, where + ": " + exception.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning("[CHRDK_Treasures] loot.yml: " + message);
        }
    }

    /* ------------------------------- встроенный ------------------------------- */

    private static Table builtin() {
        Map<TreasureTier, List<LootEntry>> global = new EnumMap<>(TreasureTier.class);
        global.put(TreasureTier.COMMON, List.of(
                entry("COAL", 3, 8), entry("IRON_INGOT", 1, 3),
                entry("TORCH", 8, 16), entry("BREAD", 1, 3)));
        global.put(TreasureTier.RARE, List.of(
                entry("IRON_INGOT", 3, 6), entry("GOLD_INGOT", 2, 4),
                entry("LAPIS_LAZULI", 4, 10), entry("AMETHYST_SHARD", 2, 5)));
        global.put(TreasureTier.EPIC, List.of(
                entry("DIAMOND", 1, 3), entry("EMERALD", 1, 3),
                entry("GOLDEN_APPLE", 1, 2), entry("EXPERIENCE_BOTTLE", 4, 10)));
        global.put(TreasureTier.LEGENDARY, List.of(
                entry("DIAMOND", 3, 6), entry("NETHERITE_SCRAP", 1, 2),
                entry("ENCHANTED_GOLDEN_APPLE", 1, 1), entry("DIAMOND_BLOCK", 1, 1)));
        global.put(TreasureTier.MYTHICAL, List.of(
                entry("NETHERITE_INGOT", 1, 2), entry("NETHERITE_SCRAP", 2, 4),
                entry("ENCHANTED_GOLDEN_APPLE", 2, 2), entry("TOTEM_OF_UNDYING", 1, 1)));

        Map<TreasureRegion, Map<TreasureTier, List<LootEntry>>> regional =
                new EnumMap<>(TreasureRegion.class);
        regional.put(TreasureRegion.PLAINS, builtinRegion(entry("WHEAT", 4, 10),
                entry("HAY_BLOCK", 1, 3)));
        regional.put(TreasureRegion.FOREST, builtinRegion(entry("OAK_LOG", 4, 10),
                entry("APPLE", 2, 5)));
        regional.put(TreasureRegion.TAIGA, builtinRegion(entry("SPRUCE_LOG", 4, 10),
                entry("SWEET_BERRIES", 3, 8)));
        regional.put(TreasureRegion.SNOW, builtinRegion(entry("SNOWBALL", 6, 16),
                entry("ICE", 2, 6)));
        regional.put(TreasureRegion.DESERT, builtinRegion(entry("SAND", 6, 16),
                entry("CACTUS", 2, 6)));
        regional.put(TreasureRegion.SAVANNA, builtinRegion(entry("ACACIA_LOG", 4, 10),
                entry("MELON_SLICE", 3, 8)));
        regional.put(TreasureRegion.JUNGLE, builtinRegion(entry("BAMBOO", 6, 16),
                entry("COCOA_BEANS", 2, 6)));
        regional.put(TreasureRegion.SWAMP, builtinRegion(entry("SLIME_BALL", 1, 4),
                entry("SEA_PICKLE", 2, 6)));
        regional.put(TreasureRegion.MOUNTAIN, builtinRegion(entry("COBBLESTONE", 8, 20),
                entry("EMERALD", 1, 2)));
        regional.put(TreasureRegion.OCEAN, builtinRegion(entry("PRISMARINE_SHARD", 2, 6),
                entry("KELP", 4, 10)));
        regional.put(TreasureRegion.DEEP, builtinRegion(entry("DEEPSLATE", 8, 20),
                entry("REDSTONE", 4, 10)));
        regional.put(TreasureRegion.DEEP_DARK, builtinRegion(entry("SCULK", 2, 6),
                entry("ECHO_SHARD", 1, 1)));
        regional.put(TreasureRegion.NETHER, builtinRegion(entry("QUARTZ", 4, 12),
                entry("BLAZE_POWDER", 2, 5)));
        regional.put(TreasureRegion.END, builtinRegion(entry("ENDER_PEARL", 1, 3),
                entry("CHORUS_FRUIT", 2, 5)));
        return new Table(global, regional);
    }

    private static Map<TreasureTier, List<LootEntry>> builtinRegion(LootEntry... entries) {
        Map<TreasureTier, List<LootEntry>> tiers = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            tiers.put(tier, tier == TreasureTier.COMMON ? List.of(entries) : List.of());
        }
        return tiers;
    }

    private static LootEntry entry(String material, int min, int max) {
        return new LootEntry(material, min, max, 1);
    }

    /* ---------------------------------- доступ -------------------------------- */

    /** Сколько отдельных бросков делает клад этой редкости. */
    public static int rolls(TreasureTier tier) {
        return switch (tier) {
            case COMMON -> 1;
            case RARE -> 2;
            case EPIC -> 2;
            case LEGENDARY -> 3;
            case MYTHICAL -> 4;
        };
    }

    /** Общий пул редкости — для тестов и диагностики. */
    public static List<LootEntry> poolOf(TreasureTier tier) {
        return active.global(tier);
    }

    /** Все региональные записи региона. */
    public static List<LootEntry> flavorOf(TreasureRegion region) {
        return region == null ? List.of() : active.regional(region);
    }

    /** Смешанный пул редкости и региона: то, из чего реально бросается клад. */
    public static List<LootEntry> entries(TreasureTier tier, TreasureRegion region) {
        return active.combined(tier, region);
    }

    /**
     * Доля записи в пуле с учётом прогресса (0..1).
     * Нужна для честного показа шансов в {@code /treasures loot}.
     */
    public static double chance(TreasureTier tier, TreasureRegion region, LootEntry entry,
                                long mined) {
        int total = 0;
        int mine = 0;
        for (LootEntry candidate : active.combined(tier, region)) {
            if (candidate.unlock() > mined) {
                continue;
            }
            total += candidate.weight();
            if (candidate.equals(entry)) {
                mine += candidate.weight();
            }
        }
        return total == 0 ? 0.0 : mine / (double) total;
    }

    /**
     * Бросает ванильную добычу без учёта прогресса — для тестов и админских путей.
     */
    public static List<LootStack> roll(TreasureTier tier, TreasureRegion region, Random random) {
        return roll(tier, region, random, Long.MAX_VALUE);
    }

    /**
     * Бросает ванильную добычу.
     *
     * <p>Биомные записи участвуют с двойным весом, одинаковые материалы
     * складываются в один стек (но не больше 64), поэтому клад не выдаёт
     * десять отдельных строк «уголь по 3».</p>
     *
     * @param mined сколько блоков добыл игрок: записи с большим порогом не выпадают
     */
    public static List<LootStack> roll(TreasureTier tier, TreasureRegion region, Random random,
                                       long mined) {
        if (tier == null || random == null) {
            return List.of();
        }
        List<LootEntry> pool = new ArrayList<>();
        for (LootEntry entry : entries(tier, region)) {
            if (entry.unlock() <= mined) {
                pool.add(entry);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        // Ключ агрегации — материал и имя: «Кровавый суп» не должен слиться
        // с обычной миской того же материала.
        Map<String, LootStack> totals = new LinkedHashMap<>();
        int picks = rolls(tier);
        for (int index = 0; index < picks; index++) {
            LootEntry entry = weighted(pool, random);
            int amount = entry.min() + random.nextInt(entry.max() - entry.min() + 1);
            LootStack stack = new LootStack(entry.material(), amount, entry.name());
            totals.merge(stack.key(), stack,
                    (left, right) -> new LootStack(left.material(),
                            left.amount() + right.amount(), left.name()));
        }
        List<LootStack> out = new ArrayList<>(totals.size());
        for (LootStack stack : totals.values()) {
            int amount = stack.amount();
            while (amount > 0) {
                int chunk = Math.min(64, amount);
                out.add(new LootStack(stack.material(), chunk, stack.name()));
                amount -= chunk;
            }
        }
        return List.copyOf(out);
    }

    private static LootEntry weighted(List<LootEntry> pool, Random random) {
        int total = 0;
        for (LootEntry entry : pool) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        int current = 0;
        for (LootEntry entry : pool) {
            current += entry.weight();
            if (roll < current) {
                return entry;
            }
        }
        return pool.get(pool.size() - 1);
    }
}

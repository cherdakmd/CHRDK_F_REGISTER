package ru.cherdakmd.chrdktreasures;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Ванильная часть клада: руда, ресурсы и биомные мелочи.
 *
 * <p>Пул редкости задаёт «вес» находки, биомный пул добавляет местный колорит
 * (в пустыне — песок и кактусы, в Нижнем мире — кварц). Биомные записи весят
 * вдвое больше, чтобы клад чувствовался принадлежащим месту, а не абстрактным
 * сундуком.</p>
 *
 * <p>Класс чистый: материалы хранятся именами, случай приходит снаружи —
 * количества и состав проверяются тестами без сервера.</p>
 */
public final class TreasureLoot {

    /** Запись пула: материал и диапазон количества. */
    public record LootEntry(String material, int min, int max, int weight) {

        public LootEntry {
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("запись добычи без материала");
            }
            if (min < 1 || max < min) {
                throw new IllegalArgumentException("кривой диапазон у " + material);
            }
            if (weight < 1) {
                throw new IllegalArgumentException("вес меньше единицы у " + material);
            }
        }
    }

    /** Готовый стек добычи. */
    public record LootStack(String material, int amount) {
    }

    private static final Map<TreasureTier, List<LootEntry>> TIER_POOLS =
            new EnumMap<>(TreasureTier.class);
    private static final Map<TreasureRegion, List<LootEntry>> REGION_POOLS =
            new EnumMap<>(TreasureRegion.class);

    static {
        pool(TreasureTier.COMMON,
                entry("COAL", 3, 8), entry("IRON_INGOT", 1, 3),
                entry("TORCH", 8, 16), entry("BREAD", 1, 3));
        pool(TreasureTier.RARE,
                entry("IRON_INGOT", 3, 6), entry("GOLD_INGOT", 2, 4),
                entry("LAPIS_LAZULI", 4, 10), entry("AMETHYST_SHARD", 2, 5));
        pool(TreasureTier.EPIC,
                entry("DIAMOND", 1, 3), entry("EMERALD", 1, 3),
                entry("GOLDEN_APPLE", 1, 2), entry("EXPERIENCE_BOTTLE", 4, 10));
        pool(TreasureTier.LEGENDARY,
                entry("DIAMOND", 3, 6), entry("NETHERITE_SCRAP", 1, 2),
                entry("ENCHANTED_GOLDEN_APPLE", 1, 1), entry("DIAMOND_BLOCK", 1, 1));
        pool(TreasureTier.MYTHICAL,
                entry("NETHERITE_INGOT", 1, 2), entry("NETHERITE_SCRAP", 2, 4),
                entry("ENCHANTED_GOLDEN_APPLE", 2, 2), entry("TOTEM_OF_UNDYING", 1, 1));

        flavor(TreasureRegion.PLAINS, entry("WHEAT", 4, 10), entry("HAY_BLOCK", 1, 3));
        flavor(TreasureRegion.FOREST, entry("OAK_LOG", 4, 10), entry("APPLE", 2, 5));
        flavor(TreasureRegion.TAIGA, entry("SPRUCE_LOG", 4, 10), entry("SWEET_BERRIES", 3, 8));
        flavor(TreasureRegion.SNOW, entry("SNOWBALL", 6, 16), entry("ICE", 2, 6));
        flavor(TreasureRegion.DESERT, entry("SAND", 6, 16), entry("CACTUS", 2, 6));
        flavor(TreasureRegion.SAVANNA, entry("ACACIA_LOG", 4, 10), entry("MELON_SLICE", 3, 8));
        flavor(TreasureRegion.JUNGLE, entry("BAMBOO", 6, 16), entry("COCOA_BEANS", 2, 6));
        flavor(TreasureRegion.SWAMP, entry("SLIME_BALL", 1, 4), entry("SEA_PICKLE", 2, 6));
        flavor(TreasureRegion.MOUNTAIN, entry("COBBLESTONE", 8, 20), entry("EMERALD", 1, 2));
        flavor(TreasureRegion.OCEAN, entry("PRISMARINE_SHARD", 2, 6), entry("KELP", 4, 10));
        flavor(TreasureRegion.DEEP, entry("DEEPSLATE", 8, 20), entry("REDSTONE", 4, 10));
        flavor(TreasureRegion.DEEP_DARK, entry("SCULK", 2, 6), entry("ECHO_SHARD", 1, 1));
        flavor(TreasureRegion.NETHER, entry("QUARTZ", 4, 12), entry("BLAZE_POWDER", 2, 5));
        flavor(TreasureRegion.END, entry("ENDER_PEARL", 1, 3), entry("CHORUS_FRUIT", 2, 5));
    }

    private TreasureLoot() {
    }

    private static void pool(TreasureTier tier, LootEntry... entries) {
        TIER_POOLS.put(tier, List.of(entries));
    }

    private static void flavor(TreasureRegion region, LootEntry... entries) {
        REGION_POOLS.put(region, List.of(entries));
    }

    private static LootEntry entry(String material, int min, int max) {
        return new LootEntry(material, min, max, 1);
    }

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

    /** Пул редкости (без биомных добавок) — для тестов и диагностики. */
    public static List<LootEntry> poolOf(TreasureTier tier) {
        return TIER_POOLS.getOrDefault(tier, List.of());
    }

    /** Биомные добавки региона. */
    public static List<LootEntry> flavorOf(TreasureRegion region) {
        return REGION_POOLS.getOrDefault(region, List.of());
    }

    /**
     * Бросает ванильную добычу.
     *
     * <p>Биомные записи участвуют с двойным весом, одинаковые материалы
     * складываются в один стек (но не больше 64), поэтому клад не выдаёт
     * десять отдельных строк «уголь по 3».</p>
     */
    public static List<LootStack> roll(TreasureTier tier, TreasureRegion region, Random random) {
        List<LootEntry> pool = new ArrayList<>(poolOf(tier));
        List<LootEntry> flavor = region == null ? List.of() : flavorOf(region);
        for (LootEntry entry : flavor) {
            pool.add(new LootEntry(entry.material(), entry.min(), entry.max(), entry.weight() * 2));
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        int picks = rolls(tier);
        for (int index = 0; index < picks; index++) {
            LootEntry entry = weighted(pool, random);
            int amount = entry.min() + random.nextInt(entry.max() - entry.min() + 1);
            totals.merge(entry.material(), amount, Integer::sum);
        }
        List<LootStack> out = new ArrayList<>(totals.size());
        totals.forEach((material, amount) ->
                out.add(new LootStack(material, Math.min(64, amount))));
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

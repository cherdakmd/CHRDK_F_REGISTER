package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Клад-карта: зона, срок, раскопки и выбор редкости (1.4.153). */
class TreasureMapTest {

    private static TreasureMap map(int x, int z, int radius, int needed, int remaining,
                                   long expiresAt) {
        return new TreasureMap(TreasureRegion.DESERT, TreasureTier.RARE, "world",
                x, z, radius, needed, remaining, expiresAt);
    }

    @Test
    void zoneIsHorizontalAndBoundByRadius() {
        TreasureMap map = map(100, -50, 24, 10, 10, 0L);
        assertTrue(map.inZone("world", 100, -50), "точка тайника всегда в зоне");
        assertTrue(map.inZone("world", 124, -50), "граница входит в зону");
        assertTrue(map.inZone("world", 100, -26));
        assertFalse(map.inZone("world", 125, -50), "за границей зоны блока нет");
        assertFalse(map.inZone("other", 100, -50), "чужой мир не считается");
        assertFalse(map.inZone(null, 100, -50));
        // Высота не участвует: пещера под тайником — тоже зона.
        assertTrue(map.inZone("world", 110, -40));
    }

    @Test
    void constructorFixesBrokenValues() {
        TreasureMap broken = new TreasureMap(null, null, null, 0, 0, 5_000, 0, 999, 0L);
        assertEquals(TreasureRegion.FOREST, broken.region(), "null-регион — лес");
        assertEquals(TreasureTier.COMMON, broken.tier(), "null-редкость — обычный тайник");
        assertEquals("", broken.world());
        assertEquals(TreasureMap.MAX_RADIUS, broken.radius(), "радиус зажат сверху");
        assertEquals(1, broken.needed(), "копать меньше одного блока нельзя");
        assertEquals(1, broken.remaining(), "остаток не больше целого");
    }

    @Test
    void digsStayInRangeAndDoNotCollapseToAConstant() {
        Random random = new Random(7L);
        for (int attempt = 0; attempt < 200; attempt++) {
            int digs = TreasureMap.digs(8, 20, random);
            assertTrue(digs >= 8 && digs <= 20, "вне диапазона: " + digs);
        }
        assertEquals(1, TreasureMap.digs(1, 2, null), "без броска берётся нижняя граница");
        assertEquals(TreasureMap.MAX_DIGS, TreasureMap.digs(100, 200, random),
                "верхняя граница — потолок раскопок");
        assertEquals(50, TreasureMap.digs(50, 10, random), "перевёрнутый диапазон не ломает карту");
    }

    @Test
    void progressAndExpiry() {
        TreasureMap map = map(0, 0, 24, 10, 4, 0L);
        assertEquals(6, map.dug());
        assertEquals(60, map.percent());
        assertFalse(map.done());
        assertFalse(map.expired(System.currentTimeMillis()), "0 — бессрочная карта");
        assertEquals(0L, map.remainingMillis(123L));

        TreasureMap last = map.withRemaining(0);
        assertTrue(last.done());
        assertEquals(100, last.percent());

        TreasureMap timed = map(0, 0, 24, 10, 10, 1_000L);
        assertFalse(timed.expired(999L));
        assertTrue(timed.expired(1_000L));
        assertEquals(500L, timed.remainingMillis(500L));
        assertEquals(0L, timed.remainingMillis(2_000L));
    }

    @Test
    void pickTierUsesNormalisedWeights() {
        Map<TreasureTier, Double> onlyEpic = new EnumMap<>(TreasureTier.class);
        onlyEpic.put(TreasureTier.EPIC, 5.0);
        assertEquals(TreasureTier.EPIC, TreasureMap.pickTier(onlyEpic, new Random(1L)));

        Map<TreasureTier, Double> empty = Map.of();
        assertEquals(TreasureTier.COMMON, TreasureMap.pickTier(empty, new Random(1L)));
        assertEquals(TreasureTier.COMMON, TreasureMap.pickTier(null, new Random(1L)));

        Map<TreasureTier, Double> negatives = new EnumMap<>(TreasureTier.class);
        negatives.put(TreasureTier.COMMON, -3.0);
        negatives.put(TreasureTier.LEGENDARY, 2.0);
        assertEquals(TreasureTier.LEGENDARY, TreasureMap.pickTier(negatives, new Random(1L)),
                "отрицательный вес не выигрывает у положительного");
    }

    @Test
    void defaultWeightsCoverEveryTier() {
        Map<TreasureTier, Double> weights = TreasuresConfig.Maps.defaultTierWeights();
        assertEquals(TreasureTier.values().length, weights.size(), "вес есть у каждой редкости");
        double total = 0.0;
        for (TreasureTier tier : TreasureTier.values()) {
            assertTrue(TreasuresConfig.Maps.defaultWeight(tier) > 0.0, "нулевой вес у " + tier.id());
            total += weights.get(tier);
        }
        assertEquals(100.0, total, 1e-9, "веса по умолчанию — проценты");
    }
}

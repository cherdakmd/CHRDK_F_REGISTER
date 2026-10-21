package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureRatesTest {

    @Test
    void chanceIsPercentTimesMultipliers() {
        assertEquals(0.01, TreasureRates.chance(1.0, 1.0, 1.0), 1e-9);
        assertEquals(0.02, TreasureRates.chance(1.0, 2.0, 1.0), 1e-9);
        assertEquals(0.015, TreasureRates.chance(1.0, 1.0, 1.5), 1e-9);
        assertEquals(0.0, TreasureRates.chance(0.0, 5.0, 2.0), 1e-9);
        assertEquals(0.0, TreasureRates.chance(-10.0, 1.0, 1.0), 1e-9);
        assertEquals(1.0, TreasureRates.chance(500.0, 10.0, 3.0), 1e-9);
    }

    @Test
    void depthBonusStartsBelowZeroAndIsCapped() {
        assertEquals(1.0, TreasureRates.depthBonus(0, 0.002, 0.5), 1e-9);
        assertEquals(1.0, TreasureRates.depthBonus(64, 0.002, 0.5), 1e-9);
        assertEquals(1.02, TreasureRates.depthBonus(-10, 0.002, 0.5), 1e-9);
        assertEquals(1.5, TreasureRates.depthBonus(-1000, 0.002, 0.5), 1e-9);
        assertEquals(1.0, TreasureRates.depthBonus(-1000, 0.0, 0.5), 1e-9);
    }

    @Test
    void rarestAvailableTierWinsFirst() {
        Map<TreasureTier, Double> chances = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            chances.put(tier, 100.0);
        }
        Random random = new Random(1L);
        assertEquals(TreasureTier.MYTHICAL,
                TreasureRates.pick(random, chances, 1.0, 1.0, 0L));
        chances.put(TreasureTier.MYTHICAL, 0.0);
        assertEquals(TreasureTier.LEGENDARY,
                TreasureRates.pick(random, chances, 1.0, 1.0, 15_000L));
        assertEquals(TreasureTier.EPIC,
                TreasureRates.pick(random, chances, 1.0, 1.0, 2_000L));
    }

    @Test
    void lockedTiersAreSkippedByProgression() {
        Map<TreasureTier, Double> onlyRare = new EnumMap<>(TreasureTier.class);
        onlyRare.put(TreasureTier.RARE, 100.0);
        assertNull(TreasureRates.pick(new Random(2L), onlyRare, 1.0, 1.0, 0L));
        assertNull(TreasureRates.pick(new Random(2L), onlyRare, 1.0, 1.0, 249L));
        assertEquals(TreasureTier.RARE,
                TreasureRates.pick(new Random(2L), onlyRare, 1.0, 1.0, 250L));
    }

    @Test
    void zeroChanceNeverRolls() {
        Map<TreasureTier, Double> chances = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            chances.put(tier, 0.0);
        }
        for (int index = 0; index < 100; index++) {
            assertNull(TreasureRates.pick(new Random(index), chances, 4.0, 2.0, 1_000_000L));
        }
    }

    @Test
    void defaultsCoverEveryTier() {
        Map<TreasureTier, Double> defaults = TreasureRates.defaults();
        assertEquals(TreasureTier.values().length, defaults.size());
        for (TreasureTier tier : TreasureTier.values()) {
            assertTrue(defaults.get(tier) > 0.0, "нет шанса у " + tier.id());
            assertEquals(tier.defaultChancePercent(), defaults.get(tier), 1e-9);
        }
    }

    @Test
    void nullAndEmptyArgsAreSafe() {
        assertNull(TreasureRates.pick(null, Map.of(), 1.0, 1.0, 0L));
        assertNull(TreasureRates.pick(new Random(3L), null, 1.0, 1.0, 0L));
        assertNull(TreasureRates.pick(new Random(3L), Map.of(), 1.0, 1.0, 0L));
    }
}

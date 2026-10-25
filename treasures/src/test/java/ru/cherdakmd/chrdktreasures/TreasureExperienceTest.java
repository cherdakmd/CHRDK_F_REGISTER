package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Склянки опыта: правило, диапазоны и бросок.
 *
 * <p>Опыт — главная награда клада в 1.4.157, поэтому проверяется не только
 * «сколько выпало», но и то, что опечатка в {@code config.yml} не может
 * выдать игроку сундук склянок: диапазоны, шанс бонуса и потолок зажимаются в
 * конструкторах {@link TreasureExperience.Range} и
 * {@link TreasureExperience.Rule}.</p>
 */
class TreasureExperienceTest {

    @Test
    void defaultsGrowWithTier() {
        TreasureExperience.Rule rule = TreasureExperience.defaults();
        assertTrue(rule.enabled(), "склянки выключены по умолчанию");
        assertEquals(1, rule.range(TreasureTier.COMMON).min());
        assertEquals(2, rule.range(TreasureTier.COMMON).max());
        assertEquals(2, rule.range(TreasureTier.RARE).min());
        assertEquals(3, rule.range(TreasureTier.EPIC).min());
        assertEquals(5, rule.range(TreasureTier.LEGENDARY).min());
        assertEquals(8, rule.range(TreasureTier.MYTHICAL).min());
        assertEquals(14, rule.range(TreasureTier.MYTHICAL).max());
        assertEquals(25, rule.bonusChancePercent());
        assertEquals(100, rule.bonusMultiplierPercent());
        assertEquals(32, rule.maxPerTreasure());
    }

    @Test
    void everyRollStaysInsideItsRange() {
        TreasureExperience.Rule rule = TreasureExperience.defaults();
        for (TreasureTier tier : TreasureTier.values()) {
            TreasureExperience.Range range = rule.range(tier);
            for (int seed = 0; seed < 300; seed++) {
                int bottles = TreasureExperience.roll(rule, tier, new Random(seed));
                assertTrue(bottles >= range.min() && bottles <= rule.maxPerTreasure(),
                        "бросок " + bottles + " вне диапазона " + tier.id());
                assertTrue(bottles > 0, "клад " + tier.id() + " остался без опыта");
            }
        }
    }

    @Test
    void treasureAlwaysGivesSomething() {
        TreasureExperience.Rule rule = TreasureExperience.defaults();
        for (TreasureTier tier : TreasureTier.values()) {
            for (int seed = 0; seed < 200; seed++) {
                assertTrue(TreasureExperience.roll(rule, tier, new Random(seed)) >= 1,
                        "клад " + tier.id() + " без склянок при seed " + seed);
            }
        }
    }

    @Test
    void bonusDoublesTheRoll() {
        TreasureExperience.Rule rule = new TreasureExperience.Rule(true,
                Map.of(TreasureTier.COMMON, new TreasureExperience.Range(4, 4)),
                100, 100, 32);
        assertEquals(8, TreasureExperience.roll(rule, TreasureTier.COMMON, new Random(1)));
    }

    @Test
    void zeroBonusChanceKeepsBaseAmount() {
        TreasureExperience.Rule rule = new TreasureExperience.Rule(true,
                Map.of(TreasureTier.RARE, new TreasureExperience.Range(3, 3)),
                0, 200, 32);
        for (int seed = 0; seed < 50; seed++) {
            assertEquals(3, TreasureExperience.roll(rule, TreasureTier.RARE, new Random(seed)));
        }
    }

    @Test
    void capTrimsTheBonus() {
        TreasureExperience.Rule rule = new TreasureExperience.Rule(true,
                Map.of(TreasureTier.MYTHICAL, new TreasureExperience.Range(30, 30)),
                100, 100, 32);
        assertEquals(32, TreasureExperience.roll(rule, TreasureTier.MYTHICAL, new Random(3)),
                "бонус обязан упираться в max-per-treasure");
    }

    @Test
    void disabledRuleGivesNothing() {
        TreasureExperience.Rule rule = TreasureExperience.disabled();
        for (TreasureTier tier : TreasureTier.values()) {
            for (int seed = 0; seed < 20; seed++) {
                assertEquals(0, TreasureExperience.roll(rule, tier, new Random(seed)),
                        "выключенные склянки всё равно выпали");
            }
        }
        assertEquals(0, TreasureExperience.roll(null, TreasureTier.COMMON, new Random(1)));
        assertEquals(0, TreasureExperience.roll(TreasureExperience.defaults(), null,
                new Random(1)));
    }

    @Test
    void zeroRangeMeansNoBottlesForThatTier() {
        Map<TreasureTier, TreasureExperience.Range> ranges =
                new EnumMap<>(TreasureExperience.defaultRanges());
        ranges.put(TreasureTier.EPIC, TreasureExperience.Range.NONE);
        TreasureExperience.Rule rule = new TreasureExperience.Rule(true, ranges, 100, 100, 32);
        for (int seed = 0; seed < 20; seed++) {
            assertEquals(0, TreasureExperience.roll(rule, TreasureTier.EPIC, new Random(seed)));
        }
        assertTrue(TreasureExperience.roll(rule, TreasureTier.COMMON, new Random(1)) > 0,
                "остальные редкости не должны пострадать");
    }

    @Test
    void brokenConfigValuesAreClamped() {
        TreasureExperience.Range range = new TreasureExperience.Range(-5, 5000);
        assertEquals(0, range.min());
        assertEquals(TreasureExperience.MAX_PER_TIER, range.max());

        TreasureExperience.Range inverted = new TreasureExperience.Range(9, 3);
        assertEquals(9, inverted.min());
        assertEquals(9, inverted.max(), "максимум не может быть меньше минимума");

        TreasureExperience.Rule rule = new TreasureExperience.Rule(true,
                Map.of(TreasureTier.COMMON, new TreasureExperience.Range(-2, -1)),
                500, -20, 0);
        assertEquals(100, rule.bonusChancePercent(), "шанс зажат в 0..100");
        assertEquals(0, rule.bonusMultiplierPercent());
        assertEquals(1, rule.maxPerTreasure(), "потолок не может быть нулевым");
        assertEquals(TreasureExperience.Range.NONE, rule.range(TreasureTier.COMMON));
    }

    @Test
    void missingTiersFallBackToDefaults() {
        TreasureExperience.Rule rule = new TreasureExperience.Rule(true,
                Map.of(TreasureTier.RARE, new TreasureExperience.Range(7, 7)), 0, 0, 32);
        assertEquals(7, rule.range(TreasureTier.RARE).min());
        assertEquals(TreasureExperience.defaults().range(TreasureTier.MYTHICAL),
                rule.range(TreasureTier.MYTHICAL));
        assertEquals(TreasureExperience.Range.NONE, rule.range(null));

        TreasureExperience.Rule empty = new TreasureExperience.Rule(true, null, 0, 0, 32);
        for (TreasureTier tier : TreasureTier.values()) {
            assertEquals(TreasureExperience.defaults().range(tier), empty.range(tier));
        }
    }

    @Test
    void rangesAreImmutableAndComplete() {
        TreasureExperience.Rule rule = TreasureExperience.defaults();
        assertEquals(TreasureTier.values().length, rule.ranges().size());
        assertEquals(new TreasureExperience.Range(0, 0), TreasureExperience.Range.NONE);
        assertEquals(0, TreasureExperience.Range.NONE.roll(new Random(1)));
    }

    @Test
    void materialIsTheVanillaExperienceBottle() {
        assertEquals("EXPERIENCE_BOTTLE", TreasureExperience.MATERIAL);
        assertEquals(64, TreasureExperience.MAX_PER_TREASURE);
    }
}

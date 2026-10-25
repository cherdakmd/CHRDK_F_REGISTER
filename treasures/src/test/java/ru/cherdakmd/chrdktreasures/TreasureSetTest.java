package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сетовые бонусы: по сету на регион, полный бонус сильнее частичного, а
 * сильнейший сет выбирается предсказуемо.
 */
class TreasureSetTest {

    @Test
    void everyRegionHasASet() {
        assertEquals(TreasureRegion.values().length, TreasureSets.all().size());
        Set<TreasureRegion> regions = new HashSet<>();
        for (TreasureSet set : TreasureSets.all()) {
            assertTrue(regions.add(set.region()), "дубль сета: " + set.region().id());
            assertTrue(set.partial().size() >= 1);
            assertTrue(set.full().size() > set.partial().size());
            assertTrue(TreasureSets.of(set.region()) == set, "сет не находится по региону");
        }
        assertEquals(TreasureRegion.values().length, regions.size());
        assertNull(TreasureSets.of(null));
    }

    @Test
    void everySetCanActuallyBeCompleted() {
        for (TreasureRegion region : TreasureRegion.values()) {
            int pieces = 0;
            for (TreasureTier tier : TreasureTier.values()) {
                if (tier.regional()) {
                    assertNotNull(TreasureCatalog.forRegionTier(region, tier),
                            "у сета " + region.id() + " нет предмета " + tier.id());
                    pieces++;
                }
            }
            assertEquals(TreasureSet.FULL_PIECES, pieces,
                    "полный сет не собирается из " + region.id());
        }
    }

    @Test
    void fullBonusIsNeverWeakerThanPartial() {
        for (TreasureSet set : TreasureSets.all()) {
            for (TreasureEffect bonus : set.partial()) {
                double full = 0.0;
                boolean found = false;
                for (TreasureEffect strong : set.full()) {
                    if (strong.kind() == bonus.kind()) {
                        full = strong.value();
                        found = true;
                    }
                }
                assertTrue(found, "полный бонус потерял вид: " + set.region().id());
                assertTrue(full >= bonus.value(),
                        "полный бонус слабее: " + set.region().id());
            }
        }
    }

    @Test
    void bonusSwitchesAtTwoAndFourPieces() {
        TreasureSet set = TreasureSets.of(TreasureRegion.DEEP);
        assertEquals(List.of(), set.bonusFor(0));
        assertEquals(List.of(), set.bonusFor(1));
        assertEquals(set.partial(), set.bonusFor(2));
        assertEquals(set.partial(), set.bonusFor(3));
        assertEquals(set.full(), set.bonusFor(4));
        assertEquals(set.full(), set.bonusFor(9));
        assertEquals(2, TreasureSet.PARTIAL_PIECES);
        assertEquals(4, TreasureSet.FULL_PIECES);
    }

    @Test
    void strongestSetWinsAndTiesAreStable() {
        Map<TreasureRegion, Integer> counts = new EnumMap<>(TreasureRegion.class);
        assertNull(TreasureSets.strongest(counts));
        counts.put(TreasureRegion.FOREST, 1);
        assertNull(TreasureSets.strongest(counts));

        counts.put(TreasureRegion.FOREST, 2);
        assertEquals(TreasureSets.of(TreasureRegion.FOREST), TreasureSets.strongest(counts));

        counts.put(TreasureRegion.PLAINS, 3);
        assertEquals(TreasureSets.of(TreasureRegion.PLAINS), TreasureSets.strongest(counts));

        Map<TreasureRegion, Integer> tie = new EnumMap<>(TreasureRegion.class);
        tie.put(TreasureRegion.OCEAN, 4);
        tie.put(TreasureRegion.DEEP, 4);
        TreasureSet winner = TreasureSets.strongest(tie);
        assertNotNull(winner);
        assertEquals(TreasureRegion.values()[Math.min(TreasureRegion.OCEAN.ordinal(),
                TreasureRegion.DEEP.ordinal())], winner.region(),
                "при равенстве побеждает первый регион перечисления");
    }

    @Test
    void bonusSumsOnlyMatchingKind() {
        TreasureSet set = TreasureSets.of(TreasureRegion.PLAINS);
        assertEquals(0.0, TreasureSets.bonus(set, 1, TreasureEffect.Kind.SPEED));
        assertEquals(1.0, TreasureSets.bonus(set, 2, TreasureEffect.Kind.SPEED));
        assertEquals(2.0, TreasureSets.bonus(set, 4, TreasureEffect.Kind.SPEED));
        assertEquals(0.0, TreasureSets.bonus(set, 4, TreasureEffect.Kind.HASTE));
        assertEquals(0.0, TreasureSets.bonus(null, 4, TreasureEffect.Kind.SPEED));
    }

    @Test
    void setEffectsAreBalanced() {
        for (TreasureSet set : TreasureSets.all()) {
            for (TreasureEffect effect : set.full()) {
                if (effect.kind().aura()) {
                    assertTrue(effect.value() >= 1.0 && effect.value() <= 3.0,
                            "уровень ауры вне 1..3: " + set.region().id());
                } else {
                    assertTrue(effect.value() > 0.0 && effect.value() <= 0.5,
                            "прибавка сета слишком велика: " + set.region().id()
                                    + "/" + effect.kind());
                }
            }
        }
    }

    @Test
    void invalidSetsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureSet(null, List.of(new TreasureEffect(
                        TreasureEffect.Kind.HASTE, 1)), List.of(new TreasureEffect(
                        TreasureEffect.Kind.HASTE, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureSet(TreasureRegion.DEEP, List.of(),
                        List.of(new TreasureEffect(TreasureEffect.Kind.HASTE, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureSet(TreasureRegion.DEEP,
                        List.of(new TreasureEffect(TreasureEffect.Kind.HASTE, 2)),
                        List.of(new TreasureEffect(TreasureEffect.Kind.HASTE, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureSet(TreasureRegion.DEEP,
                        List.of(new TreasureEffect(TreasureEffect.Kind.HASTE, 1)),
                        List.of(new TreasureEffect(TreasureEffect.Kind.HASTE, 2))));
    }
}

package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureRollTest {

    @Test
    void guaranteedItemGivesNamedFindWithoutLoot() {
        TreasureRoll.Roll roll = TreasureRoll.roll(TreasureTier.COMMON,
                TreasureRegion.PLAINS, 1.0, new Random(11L));
        assertNotNull(roll);
        assertTrue(roll.named());
        assertTrue(roll.loot().isEmpty(), "именная находка не должна дублироваться добычей");
        assertEquals(TreasureCatalog.forRegionTier(TreasureRegion.PLAINS, TreasureTier.COMMON),
                roll.item());
        assertEquals(TreasureTier.COMMON, roll.tier());
        assertEquals(TreasureRegion.PLAINS, roll.region());
    }

    @Test
    void zeroItemChanceAlwaysGivesLoot() {
        for (int seed = 0; seed < 50; seed++) {
            TreasureRoll.Roll roll = TreasureRoll.roll(TreasureTier.RARE,
                    TreasureRegion.SWAMP, 0.0, new Random(seed));
            assertNotNull(roll);
            assertFalse(roll.named());
            assertFalse(roll.loot().isEmpty());
        }
    }

    @Test
    void mythicalItemComesFromMythicalPool() {
        TreasureRoll.Roll roll = TreasureRoll.roll(TreasureTier.MYTHICAL,
                TreasureRegion.END, 1.0, new Random(5L));
        assertNotNull(roll);
        assertNotNull(roll.item());
        assertEquals(TreasureTier.MYTHICAL, roll.item().tier());
        assertNull(roll.item().region());
        assertTrue(TreasureCatalog.mythical().contains(roll.item()));
    }

    @Test
    void nullTierIsNullAndNullRandomIsEmpty() {
        assertNull(TreasureRoll.roll(null, TreasureRegion.PLAINS, 1.0, new Random(1L)));
        TreasureRoll.Roll empty = TreasureRoll.roll(TreasureTier.COMMON,
                TreasureRegion.PLAINS, 1.0, null);
        assertNotNull(empty);
        assertFalse(empty.named());
        assertTrue(empty.loot().isEmpty());
    }

    @Test
    void lootIsAlwaysImmutable() {
        TreasureRoll.Roll roll = new TreasureRoll.Roll(TreasureTier.COMMON,
                TreasureRegion.PLAINS, null, null);
        assertTrue(roll.loot().isEmpty());
        assertEquals(0, roll.loot().size());
    }
}

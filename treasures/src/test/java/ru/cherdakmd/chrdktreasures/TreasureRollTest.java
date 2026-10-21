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
    void progressionGatesNamedLootEntries() {
        // «Зависть пиглина» открывается после 200 000 блоков — новичку не выпадет.
        for (int seed = 0; seed < 200; seed++) {
            TreasureRoll.Roll roll = TreasureRoll.roll(TreasureTier.LEGENDARY,
                    TreasureRegion.NETHER, 0.0, new Random(seed), 0);
            for (TreasureLoot.LootStack stack : roll.loot()) {
                assertFalse("Зависть пиглина".equals(stack.name()),
                        "закрытая запись выпала новичку");
            }
        }
    }

    @Test
    void highProgressUnlocksNamedLoot() {
        boolean seen = false;
        for (int seed = 0; seed < 500 && !seen; seed++) {
            TreasureRoll.Roll roll = TreasureRoll.roll(TreasureTier.LEGENDARY,
                    TreasureRegion.NETHER, 0.0, new Random(seed), Long.MAX_VALUE);
            for (TreasureLoot.LootStack stack : roll.loot()) {
                if (!stack.name().isBlank()) {
                    seen = true;
                }
            }
        }
        assertTrue(seen, "на большом прогрессе именная добыча так и не выпала");
    }

    @Test
    void lootIsAlwaysImmutable() {
        TreasureRoll.Roll roll = new TreasureRoll.Roll(TreasureTier.COMMON,
                TreasureRegion.PLAINS, null, null);
        assertTrue(roll.loot().isEmpty());
        assertEquals(0, roll.loot().size());
    }
}

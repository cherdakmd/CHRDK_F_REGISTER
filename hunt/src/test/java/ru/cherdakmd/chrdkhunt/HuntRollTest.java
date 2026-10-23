package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Броски добычи охоты (1.4.155): границы, множитель семейства, шансы осколка
 * и ключа, дополнительный дроп и содержимое сундука.
 */
class HuntRollTest {

    private static HuntFamily family(double multiplier, HuntChest chest) {
        return new HuntFamily("undead", "Нежить", "Осколок кости", "Ключ склепа",
                multiplier, Material.BONE, Material.TRIAL_KEY, chest);
    }

    private static MobRule rule(int minCoins, int maxCoins, double shardChance,
                                int minShards, int maxShards, double keyChance,
                                List<HuntLoot> loot) {
        return new MobRule(EntityType.ZOMBIE, "undead", minCoins, maxCoins, shardChance,
                minShards, maxShards, keyChance, false, loot);
    }

    @Test
    void betweenIsInclusiveAndToleratesReversedBounds() {
        Random random = new Random(42L);
        for (int roll = 0; roll < 200; roll++) {
            int value = HuntRoll.between(random, 5, 1);
            assertTrue(value >= 1 && value <= 5, "вне диапазона: " + value);
        }
        assertEquals(7, HuntRoll.between(random, 7, 7));
        assertEquals(3, HuntRoll.between(null, 3, 3));
    }

    @Test
    void chanceRespectsZeroAndHundred() {
        Random random = new Random(1L);
        assertFalse(HuntRoll.chance(random, 0.0));
        assertTrue(HuntRoll.chance(random, 100.0));
        assertFalse(HuntRoll.chance(random, -5.0));
        assertTrue(HuntRoll.chance(random, 150.0));
    }

    @Test
    void killGivesCoinsShardKeyAndLoot() {
        HuntFamily family = family(1.0, null);
        MobRule rule = rule(2, 5, 100.0, 2, 3, 100.0,
                List.of(new HuntLoot(Material.ROTTEN_FLESH, 1, 2, 100.0)));
        KillResult result = HuntRoll.kill(new Random(7L), rule, family);
        assertTrue(result.coins() >= 2L && result.coins() <= 5L, "монеты: " + result.coins());
        assertTrue(result.shards() >= 2 && result.shards() <= 3, "осколки: " + result.shards());
        assertEquals(1, result.keys());
        assertEquals(1, result.drops().size());
        assertEquals(Material.ROTTEN_FLESH, result.drops().get(0).material());
        assertFalse(result.empty());
    }

    @Test
    void familyMultiplierRaisesCoinsButKeepsMinimum() {
        MobRule rule = rule(2, 5, 0.0, 1, 1, 0.0, List.of());
        KillResult doubled = HuntRoll.kill(new Random(11L), rule, family(2.0, null));
        assertTrue(doubled.coins() >= 4L && doubled.coins() <= 10L,
                "множитель не применился: " + doubled.coins());
        assertEquals(0, doubled.shards());
        assertEquals(0, doubled.keys());
        assertTrue(doubled.drops().isEmpty());

        KillResult once = HuntRoll.kill(new Random(11L), rule, family(1.0, null));
        assertTrue(once.coins() >= 2L && once.coins() <= 5L);
    }

    @Test
    void zeroChancesProduceNothing() {
        MobRule rule = rule(0, 0, 0.0, 5, 5, 0.0, List.of());
        KillResult result = HuntRoll.kill(new Random(3L), rule, family(10.0, null));
        assertTrue(result.empty(), "пустое правило не должно давать добычу");
    }

    @Test
    void nullInputsAreSafe() {
        assertTrue(HuntRoll.kill(null, null, null).empty());
        assertTrue(HuntRoll.chest(null, null).empty());
        assertTrue(HuntRoll.rollLoot(null, null).isEmpty());
    }

    @Test
    void chestGivesCoinsShardsDiamondsAndLoot() {
        HuntChest chest = new HuntChest("undead", 10, 20, 1, 2, 100.0, 2, 2,
                List.of(new HuntLoot(Material.BONE, 4, 6, 100.0)));
        ChestResult result = HuntRoll.chest(new Random(5L), chest);
        assertTrue(result.coins() >= 10L && result.coins() <= 20L, "монеты: " + result.coins());
        assertTrue(result.shards() >= 1 && result.shards() <= 2);
        assertEquals(2, result.diamonds());
        assertEquals(1, result.drops().size());
        assertTrue(result.drops().get(0).amount() >= 4);
    }

    @Test
    void chestWithoutDiamondChanceNeverGivesDiamonds() {
        HuntChest chest = new HuntChest("undead", 5, 5, 0, 0, 0.0, 3, 3, List.of());
        ChestResult result = HuntRoll.chest(new Random(9L), chest);
        assertEquals(5L, result.coins());
        assertEquals(0, result.shards());
        assertEquals(0, result.diamonds());
        assertFalse(result.empty());
    }

    @Test
    void lootChanceFiltersEntries() {
        List<HuntLoot> loot = List.of(
                new HuntLoot(Material.BONE, 1, 1, 0.0),
                new HuntLoot(Material.FEATHER, 1, 1, 100.0));
        List<HuntDrop> drops = HuntRoll.rollLoot(new Random(1L), loot);
        assertEquals(1, drops.size());
        assertEquals(Material.FEATHER, drops.get(0).material());
    }
}

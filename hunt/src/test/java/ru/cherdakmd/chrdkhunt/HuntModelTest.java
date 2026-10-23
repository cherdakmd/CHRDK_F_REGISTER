package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чистые модели охоты (1.4.155): клампы записей, пустые имена и защита от
 * бессмысленного конфига.
 */
class HuntModelTest {

    @Test
    void lootClampsAmountsAndChance() {
        HuntLoot loot = new HuntLoot(Material.BONE, -4, 5_000, 500.0);
        assertEquals(1, loot.minAmount());
        assertEquals(HuntLoot.MAX_AMOUNT, loot.maxAmount());
        assertEquals(100.0, loot.chance(), 1.0e-9);
        assertTrue(loot.rolls(null));
        HuntLoot never = new HuntLoot(Material.BONE, 2, 1, -1.0);
        assertEquals(2, never.minAmount());
        assertEquals(2, never.maxAmount());
        assertEquals(0.0, never.chance(), 1.0e-9);
        assertFalse(never.rolls(null));
        assertThrows(IllegalArgumentException.class, () -> new HuntLoot(null, 1, 1, 1.0));
    }

    @Test
    void familyFallsBackToSafeNamesAndIcons() {
        HuntFamily family = new HuntFamily(" UNDEAD ", " ", null, null, 99.0, null, null, null);
        assertEquals("undead", family.id());
        assertEquals("undead", family.displayName());
        assertEquals("Осколок", family.shardName());
        assertEquals("Ключ", family.keyName());
        assertEquals(10.0, family.coinMultiplier(), 1.0e-9);
        assertEquals(HuntFamily.DEFAULT_SHARD_ITEM, family.shardItem());
        assertEquals(HuntFamily.DEFAULT_KEY_ITEM, family.keyItem());
        assertFalse(family.hasChest());
        assertEquals("", HuntFamily.normalize(null));
    }

    @Test
    void mobRuleClampsAndZeroesDisabledShards() {
        MobRule rule = new MobRule(EntityType.ZOMBIE, "UNDEAD", -5, 1_000_000, 500.0,
                -3, 5_000, 150.0, true, null);
        assertEquals("undead", rule.familyId());
        assertEquals(0, rule.minCoins());
        assertEquals(MobRule.MAX_COINS, rule.maxCoins());
        assertEquals(100.0, rule.shardChance(), 1.0e-9);
        assertEquals(0, rule.minShards());
        assertEquals(MobRule.MAX_SHARDS, rule.maxShards());
        assertEquals(100.0, rule.keyChance(), 1.0e-9);
        assertTrue(rule.boss());
        assertTrue(rule.usable());
        assertTrue(rule.givesShards());
        assertTrue(rule.givesKey());

        MobRule noShards = new MobRule(EntityType.ZOMBIE, "undead", 1, 2, 0.0, 5, 5,
                0.0, false, List.of());
        assertEquals(0, noShards.minShards());
        assertEquals(0, noShards.maxShards());
        assertFalse(noShards.givesShards());
        assertFalse(noShards.givesKey());
        assertThrows(IllegalArgumentException.class,
                () -> new MobRule(null, "undead", 1, 1, 1.0, 1, 1, 1.0, false, null));
    }

    @Test
    void chestClampsAndDropsDiamondsWhenChanceIsZero() {
        HuntChest chest = new HuntChest("UNDEAD", -1, 9_999, -1, 99, 0.0, 5, 9, null);
        assertEquals("undead", chest.familyId());
        assertEquals(0, chest.minCoins());
        assertEquals(HuntChest.MAX_COINS, chest.maxCoins());
        assertEquals(0, chest.minShards());
        assertEquals(HuntChest.MAX_SHARDS, chest.maxShards());
        assertEquals(0, chest.minDiamonds());
        assertEquals(0, chest.maxDiamonds());
        assertFalse(chest.givesDiamonds());
        assertTrue(chest.loot().isEmpty());
        assertTrue(chest.usable(), "монеты делают сундук рабочим");
        assertFalse(new HuntChest("undead", 0, 0, 0, 0, 0.0, 0, 0, null).usable());
    }

    @Test
    void shopOfferCountsTotalPriceAndItems() {
        HuntShopOffer offer = new HuntShopOffer("BREAD", Material.BREAD, 999, 0, -3, -1);
        assertEquals("bread", offer.id());
        assertEquals(64, offer.amount());
        assertEquals(1, offer.price());
        assertEquals(0, offer.dailyLimit());
        assertEquals(0, offer.minCoinsEarned());
        assertEquals(24L, offer.totalPrice(24));
        assertEquals(64 * 8, offer.totalItems(8));
        assertEquals(1, offer.totalPrice(0), "нулевое количество считается одной покупкой");
        assertThrows(IllegalArgumentException.class,
                () -> new HuntShopOffer("x", null, 1, 1, 0, 0));
    }

    @Test
    void trophyClampsAndRequiresFamily() {
        HuntTrophy trophy = new HuntTrophy(" UNDEAD_SKULL ", " UNDEAD ", Material.SKELETON_SKULL,
                " ", null, -5, -5, -5, -5);
        assertEquals("undead_skull", trophy.id());
        assertEquals("undead", trophy.familyId());
        assertEquals("undead_skull", trophy.displayName());
        assertEquals(0, trophy.shards());
        assertEquals(0, trophy.coins());
        assertEquals(0, trophy.minCoinsEarned());
        assertEquals(0, trophy.order());
        assertTrue(trophy.lore().isEmpty());
        assertTrue(trophy.usable());

        HuntTrophy broken = new HuntTrophy("x", "", Material.BONE, "x", List.of(), 1, 1, 0, 0);
        assertFalse(broken.usable());
        assertThrows(IllegalArgumentException.class,
                () -> new HuntTrophy("x", "undead", null, "x", null, 1, 1, 0, 0));
    }

    @Test
    void resultsClampNegativeValues() {
        KillResult kill = new KillResult(-10L, -2, -1, null);
        assertEquals(0L, kill.coins());
        assertEquals(0, kill.shards());
        assertEquals(0, kill.keys());
        assertTrue(kill.drops().isEmpty());
        assertTrue(kill.empty());

        ChestResult chest = new ChestResult(-1L, 999, 999, null);
        assertEquals(0L, chest.coins());
        assertEquals(HuntChest.MAX_SHARDS, chest.shards());
        assertEquals(HuntChest.MAX_DIAMONDS, chest.diamonds());
        assertTrue(chest.drops().isEmpty());

        assertEquals(1, new HuntDrop(Material.BONE, -5).amount());
        assertEquals(64, new HuntDrop(Material.BONE, 500).amount());
        assertThrows(IllegalArgumentException.class, () -> new HuntDrop(null, 1));
    }

    @Test
    void familyWithChestReportsIt() {
        HuntChest chest = new HuntChest("undead", 1, 1, 1, 1, 0.0, 0, 0, null);
        HuntFamily family = new HuntFamily("undead", "Нежить", "Осколок", "Ключ", 1.0,
                Material.BONE, Material.TRIAL_KEY, chest);
        assertTrue(family.hasChest());
        assertNull(new HuntFamily("undead", null, null, null, 1.0,
                Material.BONE, Material.TRIAL_KEY, null).chest());
    }
}

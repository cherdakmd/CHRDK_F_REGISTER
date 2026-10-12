package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Взвешенный бросок и математика бонусов. */
class TierRollerTest {

    @Test
    void rollsRespectWeights() {
        List<TierDef.Snapshot> tiers = List.of(
                new TierDef.Snapshot("cursed", 5, -12),
                new TierDef.Snapshot("common", 90, 0),
                new TierDef.Snapshot("legendary", 5, 30));
        Random random = new Random(12);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            TierDef.Snapshot roll = TierRoller.roll(tiers, random);
            counts.merge(roll.id(), 1, Integer::sum);
        }
        double legendaryShare = counts.getOrDefault("legendary", 0) / 10_000.0;
        assertTrue(legendaryShare > 0.02 && legendaryShare < 0.10, "legendary ≈5%: " + legendaryShare);
        assertEquals(10_000, counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void zeroWeightsNeverRoll() {
        List<TierDef.Snapshot> tiers = List.of(
                new TierDef.Snapshot("cursed", 0, -12),
                new TierDef.Snapshot("legendary", 10, 30));
        Random random = new Random(3);
        for (int i = 0; i < 1000; i++) {
            assertEquals("legendary", TierRoller.roll(tiers, random).id());
        }
        assertNull(TierRoller.roll(List.of(new TierDef.Snapshot("cursed", 0, -12)), random),
                "сумма весов 0 → null");
    }

    @Test
    void tierMath() {
        assertEquals(0.06, TierMath.damageScalar(6), 0.0001);
        assertEquals(-0.12, TierMath.defenseScalar(-12), 0.0001);
        assertEquals(0.15, TierMath.toughnessScalar(30), 0.0001);
        assertEquals(0.048, TierMath.attackSpeedScalar(14), 0.001);
        assertEquals(0.0, TierMath.movementScalar(-12), 0.0001, "штраф скорости бега не даём");
        assertEquals(0.05, TierMath.movementScalar(30), 0.0001);
        assertEquals(4 * 3, TierMath.upgradeXpCost(3, 3));
        assertEquals(0 >= 1 ? 1 : 1, TierMath.upgradeXpCost(-9, 0));
    }

    @Test
    void classifierBasics() {
        assertEquals(ItemKind.SWORD, ItemKind.classify("NETHERITE_SWORD"));
        assertEquals(ItemKind.HELMET, ItemKind.classify("DIAMOND_HELMET"));
        assertEquals(ItemKind.PICKAXE, ItemKind.classify("iron_pickaxe"));
        assertEquals(ItemKind.TRIDENT, ItemKind.classify("TRIDENT"));
        assertNull(ItemKind.classify("APPLE"));
        assertTrue(ItemKind.TRIDENT.isWeapon());
        assertTrue(ItemKind.AXE.isTool() && ItemKind.AXE.isWeapon(), "топор — и то, и другое");
        assertTrue(ItemKind.BOOTS.isArmor());
    }
}

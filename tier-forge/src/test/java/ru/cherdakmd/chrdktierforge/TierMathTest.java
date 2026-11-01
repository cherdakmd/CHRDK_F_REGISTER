package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TierMathTest {

    @Test
    void fractionalLoreMatchesAllConfiguredScalarsWithoutBinaryTails() {
        assertEquals("+1.2", TierMath.signedPercent(TierMath.movementScalar(6)));
        assertEquals("+2.4", TierMath.signedPercent(TierMath.movementScalar(12)));
        assertEquals("-2.5", TierMath.signedPercent(TierMath.armorScalar(-5)));
        assertEquals("+90", TierMath.signedPercent(TierMath.miningScalar(30)));
        for (int percent = -50; percent <= 60; percent++) {
            for (double scalar : new double[]{TierMath.damageScalar(percent), TierMath.miningScalar(percent),
                    TierMath.armorScalar(percent), TierMath.toughnessScalar(percent), TierMath.movementScalar(percent)}) {
                assertEquals(scalar * 100, Double.parseDouble(TierMath.signedPercent(scalar)), 1e-10);
            }
            assertEquals(TierMath.defenseScalar(percent) * 0.5, TierMath.armorScalar(percent), 1e-12);
        }
    }

    @Test
    void rerollCostGrowsWithCurrentTier() {
        // проклятый (индекс 0) перекатывать дёшево — это жалоба на судьбу, не инвестиция
        assertEquals(5, TierMath.rerollXpCost(5, 0));
        assertEquals(15, TierMath.rerollXpCost(5, 2), "обычный (idx 2) — втрое дороже");
        assertEquals(35, TierMath.rerollXpCost(5, TierDef.indexOf("legendary")),
                "легендарный (idx 6) — в семь раз дороже базовой");
        assertTrue(TierMath.rerollXpCost(5, -1) >= 1, "неизвестный тир не уходит в ноль");
        assertEquals(1, TierMath.rerollXpCost(0, 0), "нулевая база клампится в единицу");
    }

    @Test
    void bonusScalarsStayInsideSafeBounds() {
        // админский процент клампится конфигом в −50..60, но формулы держат
        // собственные потолки: урон не больше +100%, добыча — +120%, штраф — −40%
        assertEquals(1.00, TierMath.damageScalar(60), 0.0001);
        assertEquals(1.00, TierMath.damageScalar(500), 0.0001, "потолок урона");
        assertEquals(-0.40, TierMath.damageScalar(-50), 0.0001, "дно штрафа урона");
        assertEquals(1.20, TierMath.miningScalar(60), 0.0001, "потолок добычи");
        assertEquals(-0.40, TierMath.miningScalar(-50), 0.0001, "дно штрафа добычи");
        assertEquals(0.08, TierMath.movementScalar(60), 0.0001,
                "даже админский процент не даёт больше +8% за деталь");
        assertEquals(0.0, TierMath.movementScalar(-50), 0.0001);
    }

    @Test
    void utilityLuckFollowsTheTier() {
        // 1.4.142: удача утилиты — +1 за каждые 10% тира, от −1 до +3.
        assertEquals(-1.0, TierMath.luckAmount(-12), 1e-9, "проклятый — минус удачи");
        assertEquals(0.0, TierMath.luckAmount(-5), 1e-9);
        assertEquals(0.0, TierMath.luckAmount(0), 1e-9);
        assertEquals(1.0, TierMath.luckAmount(6), 1e-9);
        assertEquals(1.0, TierMath.luckAmount(12), 1e-9);
        assertEquals(2.0, TierMath.luckAmount(20), 1e-9);
        assertEquals(3.0, TierMath.luckAmount(30), 1e-9);
        assertEquals(3.0, TierMath.luckAmount(60), 1e-9, "потолок +3");
    }

    @Test
    void percentFormattingMatchesLore() {
        assertEquals(60, TierMath.percentOf(0.6));
        assertEquals("+60", TierMath.signedPercent(0.6));
        assertEquals("-24", TierMath.signedPercent(-0.24));
        assertEquals("0", TierMath.signedPercent(0));
    }

    @Test
    void upgradeAndRerollCostsDifferSemantically() {
        // апгрейд платит за индекс НОВОГО тира (эпик → легенда = indexOf("legendary") = 6):
        // 3 * (6+1) = 21; перекат — за индекс ТЕКУЩЕГО (эпик, 5): 3 * (5+1) = 18.
        int upgrade = TierMath.upgradeXpCost(3, TierDef.indexOf("legendary"));
        int reroll = TierMath.rerollXpCost(3, TierDef.indexOf("epic"));
        assertEquals(3 * 7, upgrade);
        assertEquals(3 * 6, reroll);
        assertTrue(upgrade > reroll, "слияние эпика в легенду дороже переката эпика");
    }
}

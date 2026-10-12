package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TierMathTest {

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

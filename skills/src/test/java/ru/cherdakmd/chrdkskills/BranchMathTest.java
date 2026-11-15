package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ветки билда: стоимость уровней, пассивные бонусы и масштаб приёмов
 * ступенью престижа. Проверяются границы: потолок уровня ветки, квадратичный
 * рост силы приёмов и защита кулдауна от ухода в ноль.
 */
class BranchMathTest {

    @Test
    void branchLevelsCostOnePointEach() {
        assertEquals(0, BranchMath.spent(new int[0]));
        assertEquals(5, BranchMath.spent(new int[]{2, 0, 3, 0, 0, 0}));
        assertEquals(BuildBranch.MAX_LEVEL, BranchMath.spent(new int[]{15, 0, 0, 0, 0, 0}),
                "лишние уровни зажимаются потолком ветки");
    }

    @Test
    void passivesScaleWithBranchLevel() {
        assertEquals(1.0, BranchMath.meleeMult(0), 1e-9);
        assertEquals(1.10, BranchMath.meleeMult(5), 1e-9, "воин: +2% ближнего урона за уровень");
        assertEquals(1.20, BranchMath.meleeMult(50), 1e-9, "ветка не растёт выше потолка");
        assertEquals(1.06, BranchMath.xpMult(3), 1e-9, "маг: интеллект ускоряет опыт");
        assertEquals(1.05, BranchMath.rogueDamageMult(5), 1e-9);
        assertEquals(5.0, BranchMath.priestHealth(5), 1e-9, "жрец добавляет здоровье");
        assertEquals(0.05, BranchMath.clericMitigation(10), 1e-9, "клирик компенсирует пробивание");
    }

    @Test
    void abilityPowerGrowsQuadraticallyWithPrestige() {
        assertEquals(1.05, BranchMath.power(1, 0, 1.0), 1e-9,
                "на I ступени приём почти без усиления");
        assertEquals(4.35, BranchMath.power(3, 10, 2.0), 1e-9,
                "полная мощь — на высших ступенях престижа и ветки");
        assertEquals(6.0, BranchMath.power(10, 0, 1.0), 1e-9, "ранг престижа ограничен десяткой");
    }

    @Test
    void cooldownShrinksButNeverBelowFloor() {
        assertEquals(30, BranchMath.cooldown(30, 1));
        assertEquals(22, BranchMath.cooldown(30, 5));
        assertEquals(8, BranchMath.cooldown(30, 100), "кулдаун не уходит в ноль");
    }
}

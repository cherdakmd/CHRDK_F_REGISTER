package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Плюшки донатных рангов в классах (1.4.175).
 *
 * <p>Проверяется главное обещание: ранг меняет темп, а не силу. Поэтому все
 * числа — только скидки, каждая упирается в пол, а без ранга таблица не даёт
 * ничего.</p>
 */
class RankPerksTest {

    @Test
    void noRankChangesNothing() {
        RankPerks none = RankPerks.of(0);
        assertSame(RankPerks.NONE, none);
        assertEquals(75, none.skillRequirement(75));
        assertEquals(30, none.levelStep(30));
        assertEquals(1.0, none.cooldownFactor(), 1.0E-9);
        assertEquals(0L, none.changeCooldownSeconds(7L * 86_400L));
        assertEquals(0L, none.respecCooldownSeconds(24L * 3_600L));
        assertEquals(0, none.thresholdDiscount());
        assertEquals(0, none.levelStepDiscount());
    }

    @Test
    void everyStepIsARealImprovement() {
        int previousThreshold = Integer.MAX_VALUE;
        int previousStep = Integer.MAX_VALUE;
        double previousFactor = 2.0;
        for (int level = 1; level <= RankPerks.MAX_RANK; level++) {
            RankPerks perks = RankPerks.of(level);
            assertEquals(level, perks.level());
            int threshold = perks.skillRequirement(75);
            int step = perks.levelStep(30);
            assertTrue(threshold < previousThreshold, "порог навыка должен падать: " + level);
            assertTrue(step < previousStep, "шаг уровня должен уменьшаться: " + level);
            assertTrue(perks.cooldownFactor() < previousFactor, "откат должен падать: " + level);
            previousThreshold = threshold;
            previousStep = step;
            previousFactor = perks.cooldownFactor();
        }
        assertEquals(70, RankPerks.of(5).skillRequirement(75), "Покровитель: мастерский узел с 70");
        assertEquals(25, RankPerks.of(5).levelStep(30), "Покровитель: уровень за 25 навыков");
        assertTrue(RankPerks.of(5).cooldownFactor() >= RankPerks.MIN_COOLDOWN_FACTOR,
                "откат не может ускоряться бесконечно");
    }

    @Test
    void floorsAndCeilingsHoldOnNonsense() {
        RankPerks top = RankPerks.of(99);
        assertEquals(RankPerks.MAX_RANK, top.level());
        assertEquals(1, top.skillRequirement(3), "порог не опускается ниже единицы");
        assertEquals(0, top.skillRequirement(0), "нулевой порог остаётся нулевым");
        assertEquals(ClassConfig.MIN_LEVELS_PER_LEVEL, top.levelStep(1),
                "шаг уровня не опускается ниже минимума конфига");
        assertEquals(0L, top.changeCooldownSeconds(0L), "скидка не делает кулдаун отрицательным");
        assertEquals(0L, top.respecCooldownSeconds(0L));
        assertEquals(0L, RankPerks.NONE.changeCooldownSeconds(7L * 86_400L));
        assertSame(RankPerks.NONE, RankPerks.of(-3));
    }

    @Test
    void discountsAreExactlyTheTable() {
        RankPerks keeper = RankPerks.of(3);
        assertEquals(72, keeper.skillRequirement(75));
        assertEquals(27, keeper.levelStep(30));
        assertEquals(4L * 86_400L, keeper.changeCooldownSeconds(7L * 86_400L),
                "Хранитель меняет класс за 4 дня вместо 7");
        assertEquals(18L * 3_600L, keeper.respecCooldownSeconds(24L * 3_600L),
                "и пересобирает дерево через 18 часов вместо 24");
        assertEquals(0.90, keeper.cooldownFactor(), 1.0E-9);
    }
}

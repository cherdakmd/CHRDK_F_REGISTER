package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Арифметика фаз без Bukkit. */
final class DisasterPhasePlanTest {

    @Test
    void secondsBecomeTicksAndBudgetIsExact() {
        DisasterPhasePlan plan = DisasterPhasePlan.from(5, 20, 5, 2);
        assertEquals(3, plan.warnTicks());
        assertEquals(10, plan.activeTicks());
        assertEquals(3, plan.fadeTicks());
        assertEquals(2, plan.tickSeconds());
        assertEquals(16, plan.totalTicks());
        assertEquals(32, plan.totalSeconds());

        int sum = 0;
        for (int step = 0; step < plan.activeTicks(); step++) {
            int portion = plan.budget(25, step);
            assertTrue(portion >= 2 && portion <= 3, "порция шага " + step);
            sum += portion;
        }
        assertEquals(25, sum, "лимит блоков раскладывается без остатка");
        assertEquals(0, plan.budget(25, 99), "шагов вне активной фазы нет");
        assertEquals(0, plan.budget(0, 0));
    }

    @Test
    void zeroSecondsStillGiveOneVisibleTick() {
        DisasterPhasePlan plan = DisasterPhasePlan.from(0, 2, 0, 3);
        assertEquals(1, plan.warnTicks());
        assertEquals(1, plan.activeTicks());
        assertEquals(1, plan.fadeTicks());
        assertEquals(3, plan.totalTicks());
        assertEquals(9, plan.totalSeconds());
        assertEquals(7, plan.budget(7, 0));
    }
}

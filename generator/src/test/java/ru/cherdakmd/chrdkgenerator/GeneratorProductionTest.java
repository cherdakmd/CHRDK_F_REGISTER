package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Начисления производства: онлайн-циклы, офлайн-бюджет, прогоны и переполнение. */
class GeneratorProductionTest {

    private static final double CYCLE_MS = 30_000.0;
    private static final long BUDGET_MS = 8 * 3_600_000L;

    @Test void onlineTicksAccumulateFractionalCycles() {
        // 15 с из 30-секундного цикла — единиц нет, оплаченного времени нет:
        // метка расчёта не двигается, остаток достраивается следующим тиком.
        GeneratorProduction.Settle warm = GeneratorProduction.settle(
                15_000, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 1_000);
        assertEquals(0, warm.units());
        assertEquals(0, warm.advanceMillis());

        // Полный цикл — одна единица (база 1 ед/цикл).
        GeneratorProduction.Settle done = GeneratorProduction.settle(
                30_000, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 1_000);
        assertEquals(1, done.units());
        assertEquals(30_000, done.advanceMillis());

        // Перерождение +5% к темпу: 20 циклов дадут 21 единицу (floor(20×1.05)).
        GeneratorProduction.Settle bonus = GeneratorProduction.settle(
                600_000, CYCLE_MS, 1, 5, false, 1, BUDGET_MS, 1_000);
        assertEquals(21, bonus.units());
        assertEquals(600_000, bonus.advanceMillis());
    }

    @Test void offlineGapsUseEfficiencyAndHourBudget() {
        // Простой 8 ч при КПД 0.5: 960 циклов → 480 единиц, остаток горит.
        GeneratorProduction.Settle gap = GeneratorProduction.settle(
                BUDGET_MS, CYCLE_MS, 1, 0, false, 0.5, BUDGET_MS, 1_000);
        assertEquals(480, gap.units());
        assertEquals(BUDGET_MS, gap.advanceMillis());

        // Сутки простоя добирают только бюджет 8 ч.
        GeneratorProduction.Settle day = GeneratorProduction.settle(
                2 * BUDGET_MS, CYCLE_MS, 1, 0, false, 0.5, BUDGET_MS, 1_000);
        assertEquals(480, day.units());
        assertEquals(2 * BUDGET_MS, day.advanceMillis());
    }

    @Test void chunkModuleDropsTheBudgetAndKeepsFullEfficiency() {
        // «Чанк»: простой идёт в полный счёт без бюджета и с КПД 1.
        GeneratorProduction.Settle chunk = GeneratorProduction.settle(
                2 * BUDGET_MS, CYCLE_MS, 1, 0, true, 0.5, BUDGET_MS, 2_000);
        assertEquals(1_920, chunk.units());
        assertEquals(2 * BUDGET_MS, chunk.advanceMillis());
    }

    @Test void shortPauseStaysOnlineLongGapIsDowntime() {
        // Маленькая паука (меньше порога) продолжает считаться онлайн: остаток цикла цел.
        GeneratorProduction.Settle online = GeneratorProduction.settle(
                1, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 1_000);
        assertEquals(0, online.units());
        assertEquals(0, online.advanceMillis());

        // Паука больше порога — простой: 90 с ≈ 3 цикла выдаются и остаток горит.
        GeneratorProduction.Settle gap = GeneratorProduction.settle(
                GeneratorProduction.ONLINE_GAP_MILLIS + 1, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 1_000);
        assertEquals(3, gap.units());
        assertEquals(GeneratorProduction.ONLINE_GAP_MILLIS + 1, gap.advanceMillis());
    }

    @Test void overflowAndRoomCapBurnTheExcess() {
        // Бункер почти полон: из 20 циклов влезает только 3 единицы, время горит.
        GeneratorProduction.Settle full = GeneratorProduction.settle(
                600_000, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 3);
        assertEquals(3, full.units());
        assertEquals(600_000, full.advanceMillis());

        // Места нет совсем — всё сгорает, начисления нет.
        GeneratorProduction.Settle none = GeneratorProduction.settle(
                600_000, CYCLE_MS, 1, 0, false, 1, BUDGET_MS, 0);
        assertEquals(0, none.units());
        assertEquals(600_000, none.advanceMillis());
    }
}

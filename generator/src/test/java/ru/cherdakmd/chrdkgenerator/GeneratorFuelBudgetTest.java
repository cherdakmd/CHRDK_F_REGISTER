package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorFuelBudgetTest {
    @Test void convertsFurnaceTicksExactlyAndRejectsOverflow() {
        assertEquals(80_000, GeneratorFuelBudget.fromFurnaceTicks(1600, 1));
        assertEquals(160_000, GeneratorFuelBudget.fromFurnaceTicks(1600, 2));
        assertEquals(0, GeneratorFuelBudget.fromFurnaceTicks(1600, 0));
        assertThrows(ArithmeticException.class, () -> GeneratorFuelBudget.fromFurnaceTicks(Long.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> GeneratorFuelBudget.fromFurnaceTicks(-1, 1));
    }

    @Test void emptyAndPartialFuelNeverProduceUnpaidTime() {
        assertEquals(new GeneratorFuelBudget.Plan(0, 0, 120_000),
                GeneratorFuelBudget.plan(120_000, 0, true));
        assertEquals(new GeneratorFuelBudget.Plan(80_000, 0, 40_000),
                GeneratorFuelBudget.plan(120_000, 80_000, true));
        assertEquals(new GeneratorFuelBudget.Plan(30_000, 50_000, 0),
                GeneratorFuelBudget.plan(30_000, 80_000, true));
    }

    @Test void pausedProductionDoesNotConsumeFuelOrAccumulateDebt() {
        assertEquals(new GeneratorFuelBudget.Plan(0, 80_000, 120_000),
                GeneratorFuelBudget.plan(120_000, 80_000, false));
        assertEquals(new GeneratorFuelBudget.Plan(0, 80_000, 0),
                GeneratorFuelBudget.plan(0, 80_000, true));
        assertThrows(IllegalArgumentException.class, () -> GeneratorFuelBudget.plan(-1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> GeneratorFuelBudget.plan(1, -1, true));
    }

    @Test void arithmeticWorksAtLongBoundary() {
        assertEquals(new GeneratorFuelBudget.Plan(Long.MAX_VALUE - 1, 0, 1),
                GeneratorFuelBudget.plan(Long.MAX_VALUE, Long.MAX_VALUE - 1, true));
    }
}

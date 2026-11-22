package ru.cherdakmd.chrdkgenerator;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** План одного снимка: продукция, топливо, тара и время сохраняются вместе. */
final class GeneratorContainerProduction {
    record Plan(List<GeneratorContainerStore.Stack> slots, long burnMillis, long lastCalcMillis, long units) { }
    private GeneratorContainerProduction() { }

    static Plan plan(GeneratorContainerStore.View view, long now, double cycleMillis, long unitsPerCycle,
                     double bonus, boolean chunk, double offlineEfficiency, long offlineBudgetMillis,
                     long room, Function<String, GeneratorFurnaceFuel.Fuel> fuel,
                     Function<String, Integer> maxStack, Supplier<String> roll) {
        if (view == null || view.pending() || now < view.lastCalcMillis() || room < 0)
            throw new IllegalArgumentException("invalid generator production state");
        long elapsed = now - view.lastCalcMillis();
        if (elapsed == 0) return new Plan(view.slots(), view.burnMillis(), now, 0);
        if (room == 0) return new Plan(view.slots(), view.burnMillis(), now, 0);
        // Первый проход лишь выясняет, сколько времени физически доступно. Реальный
        // расход во втором проходе ограничен учтённым производством временем.
        var available = GeneratorFurnaceBurn.consume(view.slots(), view.burnMillis(), elapsed, fuel, maxStack);
        long budget = available.usedMillis();
        if (budget == elapsed && available.burnMillis() > 0 && budget < Long.MAX_VALUE) budget++;
        var settlement = GeneratorProduction.settleWithFuel(elapsed, cycleMillis, unitsPerCycle, bonus,
                chunk, offlineEfficiency, offlineBudgetMillis, room, budget);
        var consumed = GeneratorFurnaceBurn.consume(view.slots(), view.burnMillis(), settlement.fuelUsedMillis(), fuel, maxStack);
        var output = GeneratorBunkerMigration.plan(consumed.slots(), settlement.units(), roll, maxStack);
        return new Plan(output.slots(), consumed.burnMillis(),
                Math.addExact(view.lastCalcMillis(), settlement.advanceMillis()), output.units());
    }
}

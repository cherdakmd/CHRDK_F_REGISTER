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
        if (room == 0 || physicallyFull(view.slots(), maxStack))
            return new Plan(view.slots(), view.burnMillis(), now, 0);
        // Первый проход лишь выясняет, сколько времени физически доступно. Реальный
        // расход во втором проходе ограничен учтённым производством временем.
        // Проверить на миллисекунду дальше: на точной границе одного предмета
        // следующий уже может быть готов, и дробный цикл не должен сгореть.
        long probe = elapsed == Long.MAX_VALUE ? elapsed : elapsed + 1;
        var available = GeneratorFurnaceBurn.consume(view.slots(), view.burnMillis(), probe, fuel, maxStack);
        long budget = available.usedMillis();
        var settlement = GeneratorProduction.settleWithFuel(elapsed, cycleMillis, unitsPerCycle, bonus,
                chunk, offlineEfficiency, offlineBudgetMillis, room, budget);
        var consumed = GeneratorFurnaceBurn.consume(view.slots(), view.burnMillis(), settlement.fuelUsedMillis(), fuel, maxStack);
        var output = GeneratorBunkerMigration.plan(consumed.slots(), settlement.units(), roll, maxStack);
        return new Plan(output.slots(), consumed.burnMillis(),
                Math.addExact(view.lastCalcMillis(), settlement.advanceMillis()), output.units());
    }

    private static boolean physicallyFull(List<GeneratorContainerStore.Stack> slots, Function<String, Integer> maxStack) {
        for (int i = 0; i < GeneratorContainerStore.RESOURCE_SLOTS; i++) {
            var item = slots.get(i);
            if (item == null) return false;
            Integer max = maxStack.apply(item.material());
            if (max == null || max < 1 || max > 64 || item.amount() > max)
                throw new IllegalArgumentException("invalid container stack limit");
            if (item.amount() < max) return false;
        }
        return true;
    }
}

package ru.cherdakmd.chrdkgenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Чистое моделирование расхода печного топлива и безопасного возврата тары. */
final class GeneratorFurnaceBurn {
    record Result(List<GeneratorContainerStore.Stack> slots, long burnMillis, long usedMillis) {
        Result { slots = Collections.unmodifiableList(new ArrayList<>(slots)); }
    }
    private GeneratorFurnaceBurn() { }

    /** Расходует не больше requested миллисекунд; при отсутствии топлива останавливается. */
    static Result consume(List<GeneratorContainerStore.Stack> initial, long burnMillis, long requested,
                          Function<String, GeneratorFurnaceFuel.Fuel> lookup,
                          Function<String, Integer> maximumStack) {
        if (initial == null || initial.size() != GeneratorContainerStore.SLOTS || burnMillis < 0 || requested < 0)
            throw new IllegalArgumentException("invalid furnace burn state");
        var slots = new ArrayList<>(initial);
        long remaining = burnMillis;
        long used = 0;
        while (used < requested) {
            if (remaining == 0) {
                boolean loaded = false;
                for (int i = GeneratorContainerStore.RESOURCE_SLOTS; i < GeneratorContainerStore.SLOTS; i++) {
                    var stack = slots.get(i);
                    if (stack == null) continue;
                    var fuel = lookup.apply(stack.material());
                    if (fuel == null || !fuel.material().equals(stack.material())) continue;
                    var candidate = new ArrayList<>(slots);
                    candidate.set(i, stack.amount() == 1 ? null : new GeneratorContainerStore.Stack(stack.material(), stack.amount() - 1));
                    if (fuel.remainder() != null && !insertRemainder(candidate, fuel.remainder(), maximumStack)) continue;
                    slots = candidate;
                    remaining = fuel.burnMillis();
                    loaded = true;
                    break;
                }
                if (!loaded) break;
            }
            long step = Math.min(remaining, requested - used);
            used += step;
            remaining -= step;
        }
        return new Result(slots, remaining, used);
    }

    private static boolean insertRemainder(List<GeneratorContainerStore.Stack> slots, String material,
                                           Function<String, Integer> maximumStack) {
        Integer max = maximumStack.apply(material);
        if (max == null || max < 1 || max > 64) return false;
        for (int i = GeneratorContainerStore.RESOURCE_SLOTS; i < GeneratorContainerStore.SLOTS; i++) {
            var stack = slots.get(i);
            if (stack != null && stack.material().equals(material) && stack.amount() < max) {
                slots.set(i, new GeneratorContainerStore.Stack(material, stack.amount() + 1));
                return true;
            }
        }
        for (int i = GeneratorContainerStore.RESOURCE_SLOTS; i < GeneratorContainerStore.SLOTS; i++) {
            if (slots.get(i) == null) {
                slots.set(i, new GeneratorContainerStore.Stack(material, 1));
                return true;
            }
        }
        return false;
    }
}

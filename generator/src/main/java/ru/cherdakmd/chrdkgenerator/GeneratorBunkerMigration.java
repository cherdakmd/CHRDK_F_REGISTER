package ru.cherdakmd.chrdkgenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Порционный перенос числового бункера с durable intent и квитанцией в контейнере. */
final class GeneratorBunkerMigration {
    enum Result { READY, FULL, WAIT, CONFLICT }
    record Plan(List<GeneratorContainerStore.Stack> slots, long units) { }
    private GeneratorBunkerMigration() { }

    /** Размещает не более одной ёмкости окна, не меняя входной снимок. */
    static Plan plan(List<GeneratorContainerStore.Stack> before, long units,
                     Supplier<String> roll, Function<String, Integer> maximumStack) {
        if (before == null || before.size() != GeneratorContainerStore.SLOTS || units < 0)
            throw new IllegalArgumentException("invalid legacy bunker");
        var slots = new ArrayList<>(before);
        long added = 0;
        for (long i = 0; i < units && added < GeneratorContainerStore.RESOURCE_SLOTS * 64L; i++) {
            String material = roll.get();
            Integer limit = maximumStack.apply(material);
            if (material == null || limit == null || limit < 1 || limit > 64)
                throw new IllegalArgumentException("unsupported bunker material");
            int target = -1;
            for (int j = 0; j < GeneratorContainerStore.RESOURCE_SLOTS; j++) {
                var existing = slots.get(j);
                if (existing != null && existing.material().equals(material) && existing.amount() < limit) {
                    target = j; break;
                }
            }
            if (target < 0) for (int j = 0; j < GeneratorContainerStore.RESOURCE_SLOTS; j++) {
                if (slots.get(j) == null) { target = j; break; }
            }
            if (target < 0) break;
            var previous = slots.get(target);
            slots.set(target, new GeneratorContainerStore.Stack(material, previous == null ? 1 : previous.amount() + 1));
            added++;
        }
        return new Plan(java.util.Collections.unmodifiableList(slots), added);
    }

    /** Повтор после сбоя никогда не перебрасывает уже сохранённую порцию. */
    static Result run(GeneratorStore source, GeneratorContainerStore target, UUID owner, int slot,
                      Supplier<String> roll, Function<String, Integer> maximumStack) {
        if (!source.payoutCheckpoint() || !target.healthy()) return Result.WAIT;
        var entry = source.entry(owner, slot);
        if (entry == null || !entry.installed()) return Result.CONFLICT;
        var pending = source.containerMigration(owner, slot);
        var last = source.containerLastMigration(owner, slot);
        var view = target.view(owner, slot);
        if (view == null) {
            if (last != null || pending != null) return Result.CONFLICT;
            if (!target.create(owner, slot, entry.coreItem(), entry.lastCalcMillis())) return Result.WAIT;
            view = target.view(owner, slot);
        }
        if (view == null || !view.core().equals(entry.coreItem())) return Result.CONFLICT;
        if (pending != null) {
            if (!target.applyMigration(owner, slot, pending)) return Result.CONFLICT;
            if (!source.finishContainerMigration(owner, slot, pending.transaction())) return Result.WAIT;
            entry = source.entry(owner, slot);
            last = source.containerLastMigration(owner, slot);
            view = target.view(owner, slot);
        }
        if (!Objects.equals(last, view.lastMigration())) return Result.CONFLICT;
        if (view.pending()) return Result.WAIT;
        if (entry.bunker() == 0) return Result.READY;
        var planned = plan(view.slots(), entry.bunker(), roll, maximumStack);
        if (planned.units() == 0) return Result.FULL;
        var intent = new GeneratorStore.ContainerMigration(UUID.randomUUID(), entry.coreItem(), planned.units(),
                view.revision(), last, planned.slots());
        if (!source.prepareContainerMigration(owner, slot, intent)) return Result.WAIT;
        if (!target.applyMigration(owner, slot, intent)) return Result.WAIT;
        if (!source.finishContainerMigration(owner, slot, intent.transaction())) return Result.WAIT;
        return source.entry(owner, slot).bunker() == 0 ? Result.READY : Result.FULL;
    }
}

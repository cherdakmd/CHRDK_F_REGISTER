package ru.cherdakmd.chrdkgenerator;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

/** Порядок подключения: привязка, восстановление бункера, единый снимок производства. */
final class GeneratorContainerCoordinator {
    enum Result { READY, BACKLOG, WAIT, CONFLICT }
    private GeneratorContainerCoordinator() { }

    static Result run(GeneratorStore source, GeneratorContainerStore target, UUID owner, int slot, long now,
                      List<GeneratorDrops.Drop> drops, int luck, Function<GeneratorStore.LegacyDrops, String> legacyRoll,
                      Supplier<String> productionRoll, Function<String, Integer> stackSize,
                      Function<String, GeneratorFurnaceFuel.Fuel> fuel, LongUnaryOperator money,
                      boolean chamberWorking, double cycleMillis, long unitsPerCycle, double bonus,
                      boolean chunk, double offlineEfficiency, long offlineBudgetMillis, long capacity) {
        var binding = GeneratorContainerLifecycle.bind(source, target, owner, slot, drops, luck, now);
        if (binding == GeneratorContainerLifecycle.Result.WAIT) return Result.WAIT;
        if (binding != GeneratorContainerLifecycle.Result.READY) return Result.CONFLICT;
        var view = target.view(owner, slot);
        if (view.pending()) return Result.WAIT;
        var frozen = source.legacyDrops(owner, slot);
        if (source.entry(owner, slot).bunker() > 0 && frozen == null) return Result.CONFLICT;
        var migration = GeneratorBunkerMigration.run(source, target, owner, slot, () -> legacyRoll.apply(frozen), stackSize);
        if (migration == GeneratorBunkerMigration.Result.WAIT) return Result.WAIT;
        if (migration == GeneratorBunkerMigration.Result.CONFLICT) return Result.CONFLICT;
        view = target.view(owner, slot);
        if (view == null || now < view.lastCalcMillis()) return Result.CONFLICT;
        if (migration == GeneratorBunkerMigration.Result.FULL || !chamberWorking || view.paused()) {
            if (now == view.lastCalcMillis()) return migration == GeneratorBunkerMigration.Result.FULL ? Result.BACKLOG : Result.READY;
            if (!target.settle(owner, slot, view.core(), view.revision(), view.slots(), view.burnMillis(), now)) return Result.WAIT;
            return migration == GeneratorBunkerMigration.Result.FULL ? Result.BACKLOG : Result.READY;
        }
        long count = view.slots().subList(0, GeneratorContainerStore.RESOURCE_SLOTS).stream()
                .filter(java.util.Objects::nonNull).mapToLong(GeneratorContainerStore.Stack::amount).sum();
        long room = capacity <= count ? 0 : capacity - count;
        var plan = GeneratorContainerProduction.plan(view, now, cycleMillis, unitsPerCycle, bonus, chunk,
                offlineEfficiency, offlineBudgetMillis, room, fuel, stackSize, productionRoll);
        if (plan.lastCalcMillis() == view.lastCalcMillis() && plan.units() == 0
                && plan.burnMillis() == view.burnMillis() && plan.slots().equals(view.slots())) return Result.READY;
        long reward = money.applyAsLong(plan.units());
        if (reward < 0) return Result.CONFLICT;
        return target.settleProduction(owner, slot, view.core(), view.revision(), plan.slots(),
                plan.burnMillis(), plan.lastCalcMillis(), reward) ? Result.READY : Result.WAIT;
    }
}

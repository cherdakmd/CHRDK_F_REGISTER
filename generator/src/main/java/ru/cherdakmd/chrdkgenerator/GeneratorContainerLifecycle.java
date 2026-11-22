package ru.cherdakmd.chrdkgenerator;

import java.util.Objects;
import java.util.UUID;

/** Связь двух файлов и безопасное снятие пустого контейнера через intent/receipt. */
final class GeneratorContainerLifecycle {
    enum Result { READY, NOT_EMPTY, WAIT, CONFLICT }
    private GeneratorContainerLifecycle() { }

    static Result bind(GeneratorStore source, GeneratorContainerStore target, UUID owner, int slot,
                       java.util.List<GeneratorDrops.Drop> drops, int luck, long now) {
        if (now < 0) return Result.CONFLICT;
        if (!source.payoutCheckpoint() || !target.healthy()) return Result.WAIT;
        var entry = source.entry(owner, slot);
        if (entry == null || !entry.installed()) return Result.CONFLICT;
        var binding = source.containerBinding(owner, slot);
        var view = target.view(owner, slot);
        if (binding == null) {
            if (view != null || source.containerMigration(owner, slot) != null
                    || source.containerLastMigration(owner, slot) != null) return Result.CONFLICT;
            if (!source.beginContainerBinding(owner, slot, entry.coreItem(), drops, luck)) return Result.WAIT;
            binding = source.containerBinding(owner, slot);
        }
        if (!binding.core().equals(entry.coreItem()) || "RELEASE".equals(binding.transition())) return Result.CONFLICT;
        if ("CREATE".equals(binding.transition())) {
            if (view != null && !view.core().equals(binding.core())) return Result.CONFLICT;
            // До включения новой системы топлива не было: прошлый простой не
            // становится бесплатным рабочим временем после первой заправки.
            if (view == null && !target.create(owner, slot, binding.core(), now)) return Result.WAIT;
            if (!source.finishContainerBinding(owner, slot, binding.core())) return Result.WAIT;
            view = target.view(owner, slot);
        }
        if (view == null || !binding.core().equals(view.core())) return Result.CONFLICT;
        return Result.READY;
    }

    static Result release(GeneratorStore source, GeneratorContainerStore target, UUID owner, int slot) {
        if (!source.payoutCheckpoint() || !target.healthy()) return Result.WAIT;
        var entry = source.entry(owner, slot);
        if (entry == null || !entry.installed()) return Result.CONFLICT;
        var binding = source.containerBinding(owner, slot);
        var view = target.view(owner, slot);
        if (binding == null) return view == null && source.containerLastMigration(owner, slot) == null
                && source.containerMigration(owner, slot) == null && entry.bunker() == 0 ? Result.READY : Result.CONFLICT;
        if (!entry.coreItem().equals(binding.core()) || "CREATE".equals(binding.transition())) return Result.CONFLICT;
        if (binding.transition() == null) {
            if (view == null || !view.core().equals(binding.core())
                    || !Objects.equals(view.lastMigration(), source.containerLastMigration(owner, slot))) return Result.CONFLICT;
            var sale = target.saleState(owner, slot);
            if (view.pending() || sale.pending() != null || sale.moneyDebt() != 0 || entry.bunker() != 0
                    || source.containerMigration(owner, slot) != null
                    || view.slots().stream().anyMatch(Objects::nonNull)) return Result.NOT_EMPTY;
            if (!source.beginContainerRelease(owner, slot, binding.core())) return Result.WAIT;
            binding = source.containerBinding(owner, slot);
        }
        if (!target.removeEmpty(owner, slot, binding.core(), source.containerLastMigration(owner, slot), binding.releaseId()))
            return Result.CONFLICT;
        return source.finishContainerRelease(owner, slot, binding.core()) ? Result.READY : Result.WAIT;
    }
}

package ru.cherdakmd.chrdkskills;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Протокол перехода; игровой адаптер и блокировки действий ещё не подключены. */
final class FullResetCoordinator {
    interface Ledger extends RebirthBankPayout.Ledger {
        /** Записывает нулевую отметку без начисления денег. */
        boolean mark(String event);
    }
    interface Generator {
        /** Завершает отложенные операции и сохраняет идемпотентный сброс. */
        boolean reset(String previousEpoch, String nextEpoch, int previousCount);
    }

    private FullResetCoordinator() { }

    static boolean start(SkillStore store, String owner, String epoch, Ledger ledger, Generator generator) {
        if (store.pendingResetEpoch(owner) != null) return false;
        try {
            if (!UUID.fromString(epoch).toString().equals(epoch)) return false;
        } catch (RuntimeException error) { return false; }
        if (!RebirthBankPayout.reconcile(store, owner, ledger)) return false;
        if (!store.beginFullReset(owner, epoch)) return false;
        return resume(store, owner, ledger, generator);
    }

    static boolean resume(SkillStore store, String owner, Ledger ledger, Generator generator) {
        if (!store.checkpoint()) return false;
        String next = store.pendingResetEpoch(owner);
        if (next == null || store.pendingRebirth(owner) != null) return false;
        var cycles = store.rebirthCycles(owner);
        String previous = cycles.get(cycles.size() - 1).id();
        var rows = new LinkedHashMap<>(ledger.reservations());
        String marker = RebirthCycleJournal.marker(next);
        boolean marked = rows.containsKey(marker);
        Long existing = rows.remove(marker);
        if (marked && !Long.valueOf(0).equals(existing)) return false;
        if (!RebirthCycleJournal.matches(cycles, null, rows)) return false;
        if (!marked && !ledger.mark(marker)) return false;
        // reserve() может вернуть true для ранее существующего ключа с другой суммой.
        if (!Long.valueOf(0).equals(ledger.reservations().get(marker))) return false;
        if (!generator.reset(previous, next, store.rebirths(owner))) return false;
        return store.finishFullReset(owner, next);
    }
}

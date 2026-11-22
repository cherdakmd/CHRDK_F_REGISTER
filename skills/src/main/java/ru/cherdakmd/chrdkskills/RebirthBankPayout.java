package ru.cherdakmd.chrdkskills;

import java.util.Map;

/** Восстановление денежной награды после сохранения сброса и счётчика. */
final class RebirthBankPayout {
    interface Ledger {
        Map<String, Long> reservations();
        boolean accept(String event, long amount);
    }

    private RebirthBankPayout() { }

    static String event(int count) { return "rebirth:" + count; }

    static boolean reconcile(SkillStore store, String owner, Ledger ledger) {
        if (!store.checkpoint()) return false;
        if (store.pendingResetEpoch(owner) != null) return false;
        var pending = store.pendingRebirth(owner);
        var cycles = store.rebirthCycles(owner);
        var reservations = ledger.reservations();
        if (!RebirthCycleJournal.matches(cycles, pending, reservations)) return false;
        if (pending == null) return true;
        String epoch = cycles.get(cycles.size() - 1).id();
        String event = RebirthCycleJournal.event(epoch, pending.count());
        Long frozen = reservations.get(event);
        if (frozen != null && frozen.longValue() != pending.amount()) return false;
        if (!ledger.accept(event, pending.amount())) return false;
        return store.finishPaidRebirth(owner, pending.count(), pending.amount());
    }
}

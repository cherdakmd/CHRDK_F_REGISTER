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
        var pending = store.pendingRebirth(owner);
        var last = store.lastBankRebirth(owner);
        var reservations = ledger.reservations();
        if (last != null && !Long.valueOf(last.amount()).equals(reservations.get(event(last.count())))) return false;
        for (var entry : reservations.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("rebirth:")) return false;
            String suffix = key.substring("rebirth:".length());
            if (!suffix.matches("[1-9][0-9]{0,6}")) return false;
            int count;
            try { count = Integer.parseInt(suffix); }
            catch (NumberFormatException error) { return false; }
            if (count > 1_000_000 || entry.getValue() == null || entry.getValue() <= 0) return false;
            if (last != null && count <= last.count()) continue;
            if (pending == null || count != pending.count() || entry.getValue() != pending.amount()) return false;
        }
        if (pending == null) return true;
        Long frozen = reservations.get(event(pending.count()));
        if (frozen != null && frozen.longValue() != pending.amount()) return false;
        if (!ledger.accept(event(pending.count()), pending.amount())) return false;
        return store.finishPaidRebirth(owner, pending.count(), pending.amount());
    }
}

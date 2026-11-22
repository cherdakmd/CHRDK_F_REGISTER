package ru.cherdakmd.chrdkgenerator;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Долговечный переход от копилки к идемпотентному денежному обязательству. */
final class GeneratorPayoutCoordinator {
    static final long MAX_OBLIGATION = 1_000_000L;

    interface Ledger {
        Map<String, Long> reservations(UUID owner);
        boolean accept(String key, long amount);
    }

    private GeneratorPayoutCoordinator() { }

    static String key(UUID owner, long sequence) {
        return "payout-v2:" + owner + ":" + sequence;
    }

    /** Один технический пакет за вызов; незавершённый пакет восстанавливается первым. */
    static boolean run(GeneratorStore store, UUID owner, Ledger ledger) {
        if (store == null || owner == null || ledger == null || !store.payoutCheckpoint()) return false;
        var state = store.payoutState(owner);
        if (state == null) return false;
        Map<String, Long> reservations = ledger.reservations(owner);
        String newPrefix = "payout-v2:" + owner + ":";
        for (var entry : reservations.entrySet()) {
            if (!entry.getKey().startsWith(newPrefix)) continue;
            String suffix = entry.getKey().substring(newPrefix.length());
            if (!suffix.matches("[0-9]{1,19}")) return false;
            long sequence;
            try { sequence = Long.parseLong(suffix); }
            catch (NumberFormatException error) { return false; }
            if (!state.legacyReconciled() || sequence > state.sequence()
                    || (sequence == state.sequence() && state.pending() == 0)
                    || entry.getValue() == null || entry.getValue() <= 0) return false;
        }
        if (!state.legacyReconciled()) {
            // Ранее событие payout:<owner>:<UTC day> резервировалось до списания
            // копилки. Если day ещё не записан, списываем именно замороженную
            // сумму, а не пересчитываем её из нынешнего конфига или копилки.
            String prefix = "payout:" + owner + ":";
            var legacy = new TreeMap<Long, Long>();
            for (var entry : reservations.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                String suffix = entry.getKey().substring(prefix.length());
                if (!suffix.matches("[0-9]{1,18}")) return false;
                long day;
                try { day = Long.parseLong(suffix); }
                catch (NumberFormatException error) { return false; }
                if (entry.getValue() == null || entry.getValue() <= 0) return false;
                if (day > state.legacyDay()) legacy.put(day, entry.getValue());
            }
            for (var entry : legacy.entrySet()) {
                if (!store.reconcileLegacyPayout(owner, entry.getValue(), entry.getKey())) return false;
            }
            if (!store.finishLegacyReconciliation(owner)) return false;
            state = store.payoutState(owner);
        }
        if (state.pending() == 0) {
            if (state.money() <= 0) return true;
            if (!store.preparePayout(owner, Math.min(state.money(), MAX_OBLIGATION))) return false;
            state = store.payoutState(owner);
        }
        String event = key(owner, state.sequence());
        Long frozen = reservations.get(event);
        if (frozen != null && frozen.longValue() != state.pending()) return false;
        if (!ledger.accept(event, state.pending())) return false;
        return store.finishPayout(owner, state.sequence(), state.pending());
    }
}

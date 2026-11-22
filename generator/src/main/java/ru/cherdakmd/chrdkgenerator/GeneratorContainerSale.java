package ru.cherdakmd.chrdkgenerator;

import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongBiFunction;

/** Устойчивый outbox автопродажи конкретных предметов; не работает с числовым бункером. */
final class GeneratorContainerSale {
    interface Ledger {
        Map<String, Long> reservations(UUID owner);
        boolean accept(String key, long amount);
    }
    enum Result { IDLE, SOLD, WAIT, CONFLICT }
    private GeneratorContainerSale() { }

    static String key(UUID core, long sequence) { return "container-sale:" + core + ":" + sequence; }

    /** Восстанавливает незавершённую продажу; новую порцию продаёт только при sell=true. */
    static Result run(GeneratorContainerStore store, UUID owner, int slot, boolean sell,
                      ToLongBiFunction<String, Integer> price, Ledger ledger) {
        var view = store.view(owner, slot);
        var state = store.saleState(owner, slot);
        if (view == null || state == null) return Result.CONFLICT;
        Map<String, Long> receipts = ledger.reservations(owner);
        if (!matchesHistory(view.core(), state, receipts)) return Result.CONFLICT;
        if (state.pending() == null) {
            if (!sell || view.pending()) return Result.IDLE;
            boolean reserved = false;
            for (int i = 0; i < GeneratorContainerStore.RESOURCE_SLOTS && !reserved; i++) {
                var item = view.slots().get(i);
                if (item == null) continue;
                // Ограничение относится к обязательству, а не к дневному заработку.
                for (int count = item.amount(); count > 0; count--) {
                    long amount = price.applyAsLong(item.material(), count);
                    if (amount <= 0 || amount > GeneratorPayoutCoordinator.MAX_OBLIGATION) continue;
                    if (!store.reserveSale(owner, slot, view.core(), view.revision(), i, count, amount)) return Result.WAIT;
                    reserved = true;
                    break;
                }
            }
            if (!reserved) return Result.IDLE;
            state = store.saleState(owner, slot);
        }
        var pending = state.pending();
        String key = key(view.core(), pending.sequence());
        Long prior = receipts.get(key);
        if (prior != null && prior.longValue() != pending.amount()) return Result.CONFLICT;
        if (!ledger.accept(key, pending.amount())) return Result.WAIT;
        Long confirmed = ledger.reservations(owner).get(key);
        if (confirmed == null || confirmed.longValue() != pending.amount()) return Result.CONFLICT;
        return store.finishSale(owner, slot, pending) ? Result.SOLD : Result.WAIT;
    }

    private static boolean matchesHistory(UUID core, GeneratorContainerStore.SaleState state, Map<String, Long> receipts) {
        if (receipts == null) return false;
        String prefix = "container-sale:" + core + ":";
        long count = 0;
        for (var receipt : receipts.entrySet()) {
            if (!receipt.getKey().startsWith(prefix)) continue;
            String suffix = receipt.getKey().substring(prefix.length());
            long sequence;
            try { sequence = Long.parseLong(suffix); }
            catch (NumberFormatException error) { return false; }
            if (sequence <= 0 || !Long.toString(sequence).equals(suffix) || sequence > state.sequence()
                    || receipt.getValue() == null || receipt.getValue() <= 0
                    || receipt.getValue() > GeneratorPayoutCoordinator.MAX_OBLIGATION) return false;
            if (state.pending() != null && sequence == state.sequence()
                    && receipt.getValue().longValue() != state.pending().amount()) return false;
            count++;
        }
        long completed = state.sequence() - (state.pending() == null ? 0 : 1);
        // Каждая последовательность уникальна, так что количество и верхняя
        // граница доказывают отсутствие дыр в завершённой истории.
        boolean pendingReceipt = state.pending() != null && receipts.containsKey(key(core, state.sequence()));
        return count == completed + (pendingReceipt ? 1 : 0);
    }
}

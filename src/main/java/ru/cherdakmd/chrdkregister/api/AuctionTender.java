package ru.cherdakmd.chrdkregister.api;

import java.util.*;

/** Целочисленный немутирующий план: личный банк первым, мелкие номиналы до блоков. */
public final class AuctionTender {
    public record Plan(int[] remaining, long debit, long cash, long change) { }
    public static Plan plan(long price, long balance, int[] amounts, int[] values) {
        if (price < 2 || price > 1_000_000 || balance < 0 || balance > 1_000_000_000L
                || amounts == null || values == null || amounts.length != 36 || values.length != 36) return null;
        int[] after = amounts.clone(); var slots = new ArrayList<Integer>();
        for (int i=0; i<36; i++) {
            if (after[i] < 0 || after[i] > 64 || values[i] != 0 && values[i] != 1 && values[i] != 9) return null;
            if (values[i] != 0) slots.add(i);
        }
        slots.sort(Comparator.comparingInt(i -> values[i]));
        long debit = Math.min(price, balance), needed = price - debit, cash = 0;
        for (int slot : slots) {
            if (needed <= 0) break;
            int take = (int) Math.min(after[slot], (needed + values[slot] - 1) / values[slot]);
            after[slot] -= take; long worth = (long) take * values[slot]; cash += worth; needed -= worth;
        }
        return needed > 0 ? null : new Plan(after, debit, cash, -needed);
    }
    private AuctionTender() { }
}

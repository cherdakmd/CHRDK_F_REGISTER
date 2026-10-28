package ru.cherdakmd.chrdkclaims;

import java.util.*;

/** Немутирующий целочисленный план: /bal первым, только недостача настоящими предметами. */
final class ClaimPayment {
    record Plan(int[] remaining, long bank, long physical, long change) { }
    static Plan plan(long cost, long balance, int[] amounts, int[] values) {
        if (cost < 0 || cost > Integer.MAX_VALUE || balance < 0 || balance > 1_000_000_000L
                || amounts == null || values == null || amounts.length != values.length || amounts.length > 36) return null;
        int[] next = amounts.clone(); var order = new ArrayList<Integer>();
        for (int i=0; i<next.length; i++) {
            if (next[i] < 0 || next[i] > 64 || values[i] != 0 && values[i] != 1 && values[i] != 9) return null;
            if (values[i] > 0) order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> values[i]));
        long bank = Math.min(balance, cost), need = cost - bank, physical = 0;
        for (int slot : order) {
            if (need <= 0) break;
            long count = Math.min(next[slot], (need + values[slot] - 1) / values[slot]);
            next[slot] -= (int) count; long value = count * values[slot]; physical += value; need -= value;
        }
        return need > 0 ? null : new Plan(next, bank, physical, -need);
    }
    private ClaimPayment() { }
}

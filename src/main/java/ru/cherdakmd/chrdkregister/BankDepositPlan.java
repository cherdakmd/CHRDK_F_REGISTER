package ru.cherdakmd.chrdkregister;

/** Чистый план депозита. amount=-1 — все допустимые предметы; selected=-1 — весь storage. */
final class BankDepositPlan {
    record Plan(int[] remaining, long credit) { }
    static Plan plan(int[] amounts, long[] values, int selected, long amount, long balance) {
        if (amounts == null || values == null || amounts.length != 36 || values.length != 36
                || selected < -1 || selected >= 36 || amount != -1 && amount <= 0
                || balance < 0 || balance > BankStore.MAX_BALANCE) return null;
        int[] after = amounts.clone(); long credit = 0, left = amount;
        for (int i=0; i<36; i++) {
            if (amounts[i] < 0 || amounts[i] > 64 || values[i] < 0 || values[i] > BankStore.MAX_BALANCE) return null;
            if (selected >= 0 && i != selected || values[i] == 0) continue;
            if (amount == -1) { credit += (long) after[i] * values[i]; after[i] = 0; }
            else {
                // Числовой депозит не разменивает блоки.
                if (values[i] != 1) continue;
                int take = (int) Math.min(left, after[i]); after[i] -= take; left -= take; credit += take;
            }
        }
        return amount != -1 && left != 0 || credit <= 0 || credit > BankStore.MAX_BALANCE - balance
                ? null : new Plan(after, credit);
    }
    private BankDepositPlan() { }
}

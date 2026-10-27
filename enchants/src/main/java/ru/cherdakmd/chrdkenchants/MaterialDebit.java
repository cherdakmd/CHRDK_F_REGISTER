package ru.cherdakmd.chrdkenchants;

/** Чистый план расхода, входной снимок не изменяется даже при нехватке материалов. */
final class MaterialDebit {
    static int[] plan(int[] amounts, int required) {
        if (amounts == null || amounts.length > 36 || required <= 0 || required > 64) return null;
        int[] next = amounts.clone();
        for (int value : next) if (value < 0 || value > 64) return null;
        for (int i = 0; i < next.length && required > 0; i++) {
            int take = Math.min(next[i], required); next[i] -= take; required -= take;
        }
        return required == 0 ? next : null;
    }
    private MaterialDebit() { }
}

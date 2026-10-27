package ru.cherdakmd.chrdkenchants;

import java.util.Map;

/** Чистая арифметика цены; не зависит от Bukkit registry или состояния окна. */
final class AnvilPrice {
    private AnvilPrice() { }

    static int merge(int existing, int incoming, int maximum) {
        return existing > 0 && existing == incoming
                ? Math.min(maximum, incoming + 1) : Math.max(existing, incoming);
    }

    /** Платим за каждую реально усиленную чару, по её итоговому уровню. */
    static long surcharge(Map<String, Integer> before, Map<String, Integer> after,
                          Map<String, Integer> weights) {
        long price = 0;
        for (var entry : weights.entrySet()) {
            int level = after.getOrDefault(entry.getKey(), 0);
            if (level > before.getOrDefault(entry.getKey(), 0)) {
                price += (long) entry.getValue() * level;
            }
        }
        return price == 0 ? 0 : price + 3;
    }

    static long base(int nativeCost, int leftWork, int bookWork, int renameCost, boolean pureBook) {
        if (leftWork < 0 || bookWork < 0) throw new IllegalArgumentException("Negative prior work");
        return pureBook ? Math.max(nativeCost, (long) leftWork + bookWork + renameCost) : nativeCost;
    }

    static int total(long base, long surcharge) {
        if (base < 0 || surcharge < 0) throw new IllegalArgumentException("Negative price");
        // Saturation, а не обрезание до миллиона: цена никогда не становится дешёвой из-за overflow.
        return base >= Integer.MAX_VALUE || surcharge >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, base + surcharge);
    }

    static boolean nativeCompatible(boolean pureBook, boolean hasNativeResult, int nativeCost) {
        return pureBook || hasNativeResult && nativeCost >= 0;
    }

    static boolean allowed(int price, int maximum, boolean creative) {
        return price >= 0 && (creative || price < maximum);
    }

    static int nextWork(int left, int book, boolean cumulative) {
        if (left < 0 || book < 0) throw new IllegalArgumentException("Negative prior work");
        return cumulative ? (int) Math.min(Integer.MAX_VALUE, 2L * Math.max(left, book) + 1) : 0;
    }
}

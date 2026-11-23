package ru.cherdakmd.chrdkenchants;

import java.util.Map;

/** Чистая арифметика цены; не зависит от Bukkit registry или состояния окна. */
final class AnvilPrice {
    private AnvilPrice() { }

    /**
     * Слияние уровней одной чары: равные уровни дают +1, иначе берётся максимум —
     * как в ванильной наковальне. Потолок {@code maximum} ограничивает прирост,
     * но входные уровни никогда не режутся (2.0.16): надванильные значения
     * переживают слияние, а выше потолка не растут.
     */
    static int merge(int existing, int incoming, int maximum) {
        int merged = existing > 0 && existing == incoming
                ? incoming + 1 : Math.max(existing, incoming);
        return Math.max(Math.min(merged, maximum), Math.max(existing, incoming));
    }

    /** Слияние карт уровней по каждой чаре отдельно; порядок — сначала левая карта. */
    static <K> Map<K, Integer> merge(Map<K, Integer> left, Map<K, Integer> right,
                                     java.util.function.ToIntFunction<K> maximum) {
        Map<K, Integer> result = new java.util.LinkedHashMap<>(left);
        for (var entry : right.entrySet()) {
            int before = result.getOrDefault(entry.getKey(), 0);
            result.put(entry.getKey(),
                    merge(before, entry.getValue(), maximum.applyAsInt(entry.getKey())));
        }
        return result;
    }

    /** Слияние усиливает предмет, если хотя бы одна чара выросла относительно левой стороны. */
    static <K> boolean strengthens(Map<K, Integer> merged, Map<K, Integer> left) {
        for (var entry : merged.entrySet()) {
            if (entry.getValue() > left.getOrDefault(entry.getKey(), 0)) return true;
        }
        return false;
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

    /** A Core manual take may use currency, whose price and balance are not available here. */
    static String previewStatus(int price, int maximum, boolean creative, int level) {
        if (!allowed(price, maximum, creative)) return "preview-too-expensive";
        if (creative) return "preview-ready";
        if (price >= 40) return "preview-core-payment";
        return level >= price ? "preview-ready" : "preview-too-expensive";
    }

    static int nextWork(int left, int book, boolean cumulative) {
        if (left < 0 || book < 0) throw new IllegalArgumentException("Negative prior work");
        return cumulative ? (int) Math.min(Integer.MAX_VALUE, 2L * Math.max(left, book) + 1) : 0;
    }
}

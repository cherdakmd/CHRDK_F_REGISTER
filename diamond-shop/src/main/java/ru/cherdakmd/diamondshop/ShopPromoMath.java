package ru.cherdakmd.diamondshop;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Чистая арифметика горячей скупки: календарные сутки UTC, шестичасовая
 * детерминированная ротация товаров и потолки бонуса. Ни Bukkit, ни ввода-вывода,
 * ни состояния — всё проверяется юнит-тестами.
 *
 * <p>Окно намеренно совпадает с окном персонального дневного оборота
 * {@link ShopStore}: {@code dayStart} считается тем же делением на 86 400 000 мс.</p>
 */
final class ShopPromoMath {

    static final long DAY_MILLIS = 86_400_000L;
    static final long WINDOW_MILLIS = 21_600_000L;
    static final long MAX_BONUS_VALUE = 1_000_000_000L;

    /** Горячая скупка действует ежедневно; суточный лимит и окно товаров независимы. */
    enum Type {
        HOT_BUYBACK("Горячая скупка · 5 ресурсов · 6 ч");

        private final String label;

        Type(String label) { this.label = label; }

        String label() { return label; }
    }

    /** План на конкретное время: сутки лимита и набор текущего шестичасового окна. */
    record Plan(Type type, long day, List<String> featured) { }

    static long dayIndex(long nowMillis) {
        return Math.floorDiv(nowMillis, DAY_MILLIS);
    }

    static long dayStart(long nowMillis) {
        return dayIndex(nowMillis) * DAY_MILLIS;
    }

    static Type typeFor(long day) {
        return Type.HOT_BUYBACK;
    }

    static long windowIndex(long nowMillis) {
        return Math.floorDiv(nowMillis, WINDOW_MILLIS);
    }

    /**
     * Непрерывные смены по пулу: при pool >= 2·count соседние окна не пересекаются.
     * Один UTC-window на всех серверах даёт одинаковый набор без случайности.
     */
    static List<String> featured(List<String> pool, int count, long window) {
        if (pool == null || pool.isEmpty() || count <= 0) {
            return List.of();
        }
        int size = pool.size();
        int want = Math.min(count, size);
        if (size == 1) {
            return List.of(pool.get(0));
        }
        int start = (int) Math.floorMod(window * (long) want, size);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int k = 0; k < want; k++) {
            out.add(pool.get((start + k) % size));
        }
        return List.copyOf(out);
    }

    /** Процент от выплаты за сделку, целыми алмазами. */
    static long percentOf(long base, int percent) {
        if (base <= 0 || percent <= 0) {
            return 0;
        }
        return Math.min(MAX_BONUS_VALUE, Math.max(0L, Math.round(base * (double) percent / 100.0)));
    }

    /**
     * Общий потолок бонуса. Главное ограничение — {@code noArbitrageCeiling}:
     * выплата за сделку не может превысить цену покупки того же числа партий,
     * поэтому «купить в магазине и сразу продать дороже» остаётся невозможным.
     */
    static long cap(long desired, long noArbitrageCeiling, long perTradeCap,
                    long dailyRemaining, long turnoverHeadroom) {
        long value = Math.min(Math.max(0, desired), Math.max(0, noArbitrageCeiling));
        value = Math.min(value, Math.max(0, perTradeCap));
        value = Math.min(value, Math.max(0, dailyRemaining));
        value = Math.min(value, Math.max(0, turnoverHeadroom));
        return Math.max(0, value);
    }

    /** Сколько стоило бы купить это же число единиц; потолок против арбитража. */
    static long askTotal(long askPerLot, int batch, long units) {
        if (askPerLot <= 0 || batch <= 0 || units <= 0) {
            return 0;
        }
        return Math.min(MAX_BONUS_VALUE, askPerLot * units / batch);
    }

    /** Сколько полных партий в сделке (минимум одна — бонус не делится на ноль). */
    static long lots(int batch, long units) {
        return Math.max(1, units / Math.max(1, batch));
    }

    private ShopPromoMath() { }
}

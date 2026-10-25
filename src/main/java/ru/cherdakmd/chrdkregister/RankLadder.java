package ru.cherdakmd.chrdkregister;

/**
 * Арифметика лестницы донатных рангов (1.4.167).
 *
 * <p>Ранги пришли на смену проходке: вход на сервер свободный, а ранг — это
 * подписка на комфорт, оформление и статус. Лестница состоит из пяти ступеней,
 * каждая включает предыдущую. Здесь живут только чистые расчёты, без Bukkit и
 * без конфигов: сколько дней нового ранга даёт остаток старого при повышении и
 * какой ранг положен тому, кто уже платил за проходку.</p>
 *
 * <p>Класс покрыт юнит-тестами {@code RankLadderTest}.</p>
 */
public final class RankLadder {

    /** Верхняя ступень лестницы. */
    public static final int MAX_LEVEL = 5;

    /** Порог «платил много»: 1 400 ₽ за всё время — Хранитель. */
    public static final long LEVEL_THREE_KOPECKS = 140_000L;

    /** Порог «платил»: 400 ₽ за всё время — Следопыт. */
    public static final long LEVEL_TWO_KOPECKS = 40_000L;

    private RankLadder() {
    }

    /**
     * Постоянный ранг за суммарные платежи (миграция с проходки).
     *
     * @param paidKopecks сколько игрок заплатил за всё время, копейки
     * @return 0 — не платил, 1..3 — ступень благодарности
     */
    public static int levelForLifetime(long paidKopecks) {
        if (paidKopecks >= LEVEL_THREE_KOPECKS) {
            return 3;
        }
        if (paidKopecks >= LEVEL_TWO_KOPECKS) {
            return 2;
        }
        if (paidKopecks > 0) {
            return 1;
        }
        return 0;
    }

    /**
     * Сколько дней нового ранга стоит остаток старого при повышении.
     *
     * <p>Остаток пересчитывается по цене: двести дней дешёвого ранга не должны
     * превращаться в двести дней дорогого. Дробная часть отбрасывается, потолок —
     * сам остаток (дешёвый ранг никогда не даёт больше дней, чем у него осталось).</p>
     *
     * @param remainingDays сколько дней старого ранга ещё действует
     * @param oldPrice      цена старого ранга за период
     * @param newPrice      цена нового ранга за тот же период
     * @return дни, которые добавляются к купленному сроку нового ранга
     */
    public static int carryDays(int remainingDays, int oldPrice, int newPrice) {
        if (remainingDays <= 0 || oldPrice <= 0 || newPrice <= 0 || oldPrice >= newPrice) {
            return 0;
        }
        long carried = (long) remainingDays * oldPrice / newPrice;
        return (int) Math.min(remainingDays, Math.max(0L, carried));
    }

    /**
     * Срок нового ранга после повышения: купленные дни плюс пересчитанный остаток,
     * но не дальше общего потолка каталога.
     */
    public static int upgradeDays(int boughtDays, int remainingDays, int oldPrice, int newPrice,
                                  int maxTotalDays) {
        int days = Math.max(0, boughtDays) + carryDays(remainingDays, oldPrice, newPrice);
        int cap = Math.max(1, maxTotalDays);
        return Math.min(days, cap);
    }

    /** Уровень в допустимых границах: 1..{@link #MAX_LEVEL}. */
    public static int clampLevel(int level) {
        return Math.max(1, Math.min(MAX_LEVEL, level));
    }
}

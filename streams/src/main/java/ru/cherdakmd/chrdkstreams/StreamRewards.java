package ru.cherdakmd.chrdkstreams;

/**
 * Арифметика наград стримера: сколько суток проходки даёт накопленный эфир.
 *
 * <p>Класс чистый — ни Bukkit, ни сети, ни файлов. Благодаря этому правила
 * проверяются юнит-тестами: часы, потолки, переход через полночь, защита от
 * «начислить сутки за один тик после простоя».</p>
 *
 * <p>Правила:</p>
 * <ul>
 *   <li>{@code minutesPerDay} минут эфира = 1 сутки проходки (по умолчанию 60);</li>
 *   <li>за календарные сутки (UTC) — не больше {@code maxDaysPerDay} наград;</li>
 *   <li>за всё время — не больше {@code maxTotalDays} (0 — без потолка);</li>
 *   <li>остаток копится, но не больше «почти полного дня»: накрутить запас
 *       на неделю вперёд нельзя;</li>
 *   <li>при смене суток счётчик «сегодня» обнуляется, а остаток минут — нет:
 *       честно доигранные 59 минут не сгорают в полночь.</li>
 * </ul>
 */
final class StreamRewards {

    static final long MINUTE_MILLIS = 60_000L;
    static final long DAY_MILLIS = 86_400_000L;

    /** Разумные границы, чтобы опечатка в конфиге не сломала арифметику. */
    static final int MIN_MINUTES_PER_DAY = 1;
    static final int MAX_MINUTES_PER_DAY = 600;
    static final int MAX_DAYS_PER_DAY = 24;
    static final int MAX_TOTAL_DAYS = 3650;

    private StreamRewards() {
    }

    /** Что накопилось у игрока: день (UTC epoch day), награды за день, остаток минут, всего. */
    record State(int day, int awardedToday, long pendingMillis, int totalDays) {

        static State empty() {
            return new State(0, 0, 0L, 0);
        }
    }

    /** Правила начисления из конфига, уже зажатые в допустимые границы. */
    record Rule(int minutesPerDay, int maxDaysPerDay, int maxTotalDays) {

        static Rule of(int minutesPerDay, int maxDaysPerDay, int maxTotalDays) {
            return new Rule(
                    Math.clamp(minutesPerDay, MIN_MINUTES_PER_DAY, MAX_MINUTES_PER_DAY),
                    Math.clamp(maxDaysPerDay, 0, MAX_DAYS_PER_DAY),
                    Math.clamp(maxTotalDays, 0, MAX_TOTAL_DAYS));
        }

        /** Порог одного дня проходки в миллисекундах эфира. */
        long dayThresholdMillis() {
            return (long) minutesPerDay * MINUTE_MILLIS;
        }

        /** Сколько ещё можно наградить сегодня. */
        int remainingToday(int awardedToday) {
            return Math.max(0, maxDaysPerDay - Math.max(0, awardedToday));
        }

        /** Сколько ещё можно наградить всего; {@link Integer#MAX_VALUE} — потолка нет. */
        int remainingTotal(int totalDays) {
            if (maxTotalDays <= 0) {
                return Integer.MAX_VALUE;
            }
            return Math.max(0, maxTotalDays - Math.max(0, totalDays));
        }
    }

    /** Итог шага: новое состояние и сколько суток начислить прямо сейчас. */
    record Decision(State state, int awardDays) {

        boolean awarded() {
            return awardDays > 0;
        }
    }

    /**
     * Учитывает новый отрезок эфира.
     *
     * @param state        что было
     * @param creditMillis сколько эфира добавить (уже проверенного: канал подтверждён,
     *                     игрок онлайн, эфир идёт)
     * @param nowMillis    текущее время (для определения суток)
     * @param rule         правила сезона
     */
    static Decision advance(State state, long creditMillis, long nowMillis, Rule rule) {
        int today = dayOf(nowMillis);
        boolean newDay = today != state.day();
        int awardedToday = newDay ? 0 : Math.max(0, state.awardedToday());
        long pending = Math.max(0L, state.pendingMillis());
        int totalDays = Math.max(0, state.totalDays());

        if (creditMillis > 0) {
            pending += creditMillis;
        }

        int remaining = Math.min(rule.remainingToday(awardedToday), rule.remainingTotal(totalDays));
        long threshold = rule.dayThresholdMillis();
        int possible = threshold <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, pending / threshold);
        int award = Math.min(possible, remaining);

        if (award > 0) {
            pending -= (long) award * threshold;
            awardedToday += award;
            totalDays += award;
        }

        // Не копим больше «почти дня»: иначе после потолка освободившиеся сутки
        // выдавались бы одной пачкой за эфир, который уже не засчитывался.
        long cap = Math.max(0L, threshold - 1);
        if (pending > cap) {
            pending = cap;
        }

        return new Decision(new State(today, awardedToday, pending, totalDays), award);
    }

    /** Номер суток UTC — по нему обнуляется дневной счётчик. */
    static int dayOf(long nowMillis) {
        return (int) Math.floorDiv(Math.max(0L, nowMillis), DAY_MILLIS);
    }

    /** Минут осталось до следующей награды (для /stream status). */
    static long minutesToNextDay(State state, Rule rule) {
        long threshold = rule.dayThresholdMillis();
        if (threshold <= 0) {
            return 0;
        }
        long left = Math.max(0L, threshold - Math.max(0L, state.pendingMillis()));
        return (left + MINUTE_MILLIS - 1) / MINUTE_MILLIS;
    }

    /** Минут накоплено (для /stream status): всегда меньше порога одного дня. */
    static long pendingMinutes(State state, Rule rule) {
        long threshold = rule.dayThresholdMillis();
        if (threshold <= 0) {
            return 0;
        }
        return Math.max(0L, Math.min(state.pendingMillis(), threshold - 1)) / MINUTE_MILLIS;
    }
}

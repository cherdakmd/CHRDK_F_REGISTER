package ru.cherdakmd.chrdkstreams;

/**
 * Арифметика наград стримера: сколько ЧАСОВ буста даёт накопленный эфир.
 *
 * <p>Класс чистый — ни Bukkit, ни сети, ни файлов. Благодаря этому правила
 * проверяются юнит-тестами: часы, потолки, переход через полночь, защита от
 * «начислить час за один тик после простоя».</p>
 *
 * <p>Правила:</p>
 * <ul>
 *   <li>{@code minutesPerHour} минут эфира = 1 час буста (по умолчанию 60:
 *       час эфира даёт ровно час буста);</li>
 *   <li>за календарные сутки (UTC) — не больше {@code maxHoursPerDay} часов;</li>
 *   <li>за всё время — не больше {@code maxTotalHours} (0 — без потолка);</li>
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
    static final int MIN_MINUTES_PER_HOUR = 1;
    static final int MAX_MINUTES_PER_HOUR = 600;
    static final int MAX_HOURS_PER_DAY = 24;
    static final int MAX_TOTAL_HOURS = 3650;

    private StreamRewards() {
    }

    /** Что накопилось у игрока: день (UTC epoch day), часы за день, остаток минут, всего часов. */
    record State(int day, int awardedToday, long pendingMillis, int totalHours) {

        static State empty() {
            return new State(0, 0, 0L, 0);
        }
    }

    /** Правила начисления из конфига, уже зажатые в допустимые границы. */
    record Rule(int minutesPerHour, int maxHoursPerDay, int maxTotalHours) {

        static Rule of(int minutesPerHour, int maxHoursPerDay, int maxTotalHours) {
            return new Rule(
                    Math.clamp(minutesPerHour, MIN_MINUTES_PER_HOUR, MAX_MINUTES_PER_HOUR),
                    Math.clamp(maxHoursPerDay, 0, MAX_HOURS_PER_DAY),
                    Math.clamp(maxTotalHours, 0, MAX_TOTAL_HOURS));
        }

        /** Порог одного часа буста в миллисекундах эфира. */
        long hourThresholdMillis() {
            return (long) minutesPerHour * MINUTE_MILLIS;
        }

        /** Сколько ещё часов можно наградить сегодня. */
        int remainingToday(int awardedToday) {
            return Math.max(0, maxHoursPerDay - Math.max(0, awardedToday));
        }

        /** Сколько ещё часов можно наградить всего; {@link Integer#MAX_VALUE} — потолка нет. */
        int remainingTotal(int totalHours) {
            if (maxTotalHours <= 0) {
                return Integer.MAX_VALUE;
            }
            return Math.max(0, maxTotalHours - Math.max(0, totalHours));
        }
    }

    /** Итог шага: новое состояние и сколько часов начислить прямо сейчас. */
    record Decision(State state, int awardHours) {

        boolean awarded() {
            return awardHours > 0;
        }
    }

    /**
     * Учитывает новый отрезок эфира.
     *
     * @param state        что было
     * @param creditMillis сколько эфира добавить (уже проверенного: канал подтверждён,
     *                     игрок онлайн, эфир идёт)
     * @param nowMillis    текущее время (для определения суток UTC)
     * @param rule         правила сезона
     */
    static Decision advance(State state, long creditMillis, long nowMillis, Rule rule) {
        int today = dayOf(nowMillis);
        boolean newDay = today != state.day();
        int awardedToday = newDay ? 0 : Math.max(0, state.awardedToday());
        long pending = Math.max(0L, state.pendingMillis());
        int totalHours = Math.max(0, state.totalHours());

        if (creditMillis > 0) {
            pending += creditMillis;
        }

        int remaining = Math.min(rule.remainingToday(awardedToday), rule.remainingTotal(totalHours));
        long threshold = rule.hourThresholdMillis();
        int possible = threshold <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, pending / threshold);
        int award = Math.min(possible, remaining);

        if (award > 0) {
            pending -= (long) award * threshold;
            awardedToday += award;
            totalHours += award;
        }

        // Не копим больше «почти часа»: иначе после потолка освободившиеся часы
        // выдавались бы одной пачкой за эфир, который уже не засчитывался.
        long cap = Math.max(0L, threshold - 1);
        if (pending > cap) {
            pending = cap;
        }

        return new Decision(new State(today, awardedToday, pending, totalHours), award);
    }

    /** Номер суток UTC — по нему обнуляется дневной счётчик. */
    static int dayOf(long nowMillis) {
        return (int) Math.floorDiv(Math.max(0L, nowMillis), DAY_MILLIS);
    }

    /** Минут осталось до следующего часа буста (для /stream status). */
    static long minutesToNextHour(State state, Rule rule) {
        long threshold = rule.hourThresholdMillis();
        if (threshold <= 0) {
            return 0;
        }
        long left = Math.max(0L, threshold - Math.max(0L, state.pendingMillis()));
        return (left + MINUTE_MILLIS - 1) / MINUTE_MILLIS;
    }

    /**
     * Когда примерно будет следующая награда, если эфир не прервётся.
     *
     * <p>Возвращает {@code now}, если порог уже набран (награда придёт на
     * следующем опросе), и {@code -1}, если действует потолок — считать нечего.
     * Для подсказки «следующий час буста примерно в HH:mm».</p>
     */
    static long etaMillis(State state, Rule rule, long nowMillis) {
        long threshold = rule.hourThresholdMillis();
        if (threshold <= 0) {
            return -1L;
        }
        int remaining = Math.min(rule.remainingToday(state.awardedToday()),
                rule.remainingTotal(state.totalHours()));
        if (remaining <= 0) {
            return -1L;
        }
        long left = Math.max(0L, threshold - Math.max(0L, state.pendingMillis()));
        return nowMillis + left;
    }

    /** Минут накоплено (для /stream status): всегда меньше порога одного дня. */
    static long pendingMinutes(State state, Rule rule) {
        long threshold = rule.hourThresholdMillis();
        if (threshold <= 0) {
            return 0;
        }
        return Math.max(0L, Math.min(state.pendingMillis(), threshold - 1)) / MINUTE_MILLIS;
    }
}

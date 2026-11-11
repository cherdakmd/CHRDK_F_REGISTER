package ru.cherdakmd.chrdkdisasters;

/**
 * Чистая арифметика прокачки «стойкости» и осколков. Без Bukkit и без часов:
 * дата приходит строкой, случайность решает вызывающий. Всё покрыто JUnit.
 *
 * <p>Уровень растёт по треугольным числам: на уровень L нужно
 * {@code base * (L-1) * L / 2} суммарного опыта. Первый уровень даётся сразу,
 * потолок — {@code max-level}, после него опыт копится, но уровень не растёт.</p>
 */
public final class DisasterProgressMath {

    private DisasterProgressMath() {
    }

    public static long cumulativeXp(int level, int base) {
        int safeLevel = Math.max(1, level);
        long safeBase = Math.max(1L, base);
        return safeBase * (safeLevel - 1L) * safeLevel / 2L;
    }

    public static int levelForXp(long xp, int base, int maxLevel) {
        int safeMax = Math.max(1, maxLevel);
        int level = 1;
        while (level < safeMax && cumulativeXp(level + 1, base) <= Math.max(0L, xp)) {
            level++;
        }
        return level;
    }

    public static long xpToNext(long xp, int level, int base, int maxLevel) {
        if (level >= maxLevel) {
            return 0L;
        }
        return Math.max(0L, cumulativeXp(level + 1, base) - Math.max(0L, xp));
    }

    /** Награда за одну пережитую катастрофу с бонусом стойкости. */
    public static long shardReward(int disasterLevel, int steadinessLevel,
                                   int base, int perLevel, double bonusCap) {
        long raw = Math.max(0L, base) + (long) Math.max(0, disasterLevel - 1) * Math.max(0, perLevel);
        double bonus = Math.min(Math.max(1.0, bonusCap),
                1.0 + 0.05 * Math.max(0, steadinessLevel - 1));
        return Math.max(0L, Math.round(raw * bonus));
    }

    /** Локальное событие усиливается для группы, но бонус и потолок строго ограничены. */
    public static int scaledLevel(int baseLevel, int nearbyPlayers, int maxLevel) {
        int bonus = nearbyPlayers >= 6 ? 2 : nearbyPlayers >= 3 ? 1 : 0;
        return Math.min(Math.max(1, maxLevel), Math.max(1, baseLevel) + bonus);
    }

    /** Сколько осколков ещё можно получить сегодня. */
    public static long dailyLeft(long dailyEarned, int dailyCap) {
        return Math.max(0L, dailyCap - Math.max(0L, dailyEarned));
    }

    /** Высокий уровень стойкости ослабляет мягкие эффекты состояния. */
    public static double effectScale(int level) {
        return Math.max(0.4, 1.0 - 0.08 * Math.max(0, level - 1));
    }

    /** Шанс, что расписание пропустит катастрофу у опытного игрока. */
    public static double avoidChance(int level) {
        return Math.min(0.25, 0.03 * Math.max(0, level - 1));
    }

    public static String dayKey(long epochMillis) {
        // МСК: тот же часовой пояс, что в логах движка.
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toLocalDate().toString();
    }
}

package ru.cherdakmd.chrdkregister;

/** Арифметика отдельной игровой награды; не знает о рангах и покупках. */
final class RewardBoostMath {
    static final long HOUR = 3_600_000L;
    static final int PERCENT = 10;
    private RewardBoostMath() { }

    static long grant(long remaining, int hours, int bankHours) {
        long cap = Math.clamp(bankHours, 1, 24 * 365) * HOUR;
        long safe = Math.clamp(remaining, 0L, cap);
        return hours <= 0 ? safe : safe + Math.min(cap - safe, Math.min(hours, 24 * 365) * HOUR);
    }

    static long limit(long base, long current) {
        if (base <= 0 || current <= 0) return current;
        long half = base / 2 + base % 2;
        long ceiling = base > Long.MAX_VALUE - half ? Long.MAX_VALUE : base + half;
        return Math.min(current, ceiling);
    }

    record Experience(long amount, double fraction) { }

    /** +10 % от базы, а не от уже усиленного опыта. Общий предел +50 %, с округлением до единицы XP. */
    static Experience experience(long base, long current, double fraction) {
        if (base <= 0 || current <= 0) return new Experience(current, fraction);
        double credit = Math.max(0, Math.min(1, Double.isFinite(fraction) ? fraction : 0)) + base * .10;
        current = limit(base, current);
        long ceiling = limit(base, Long.MAX_VALUE);
        long room = Math.max(0, ceiling - current);
        long extra = Math.min(room, (long) Math.floor(credit + 1e-9));
        // Не копим долг из бонусов, упёршихся в потолок. Сохраняем лишь дробную часть.
        double remainder = credit - Math.floor(credit + 1e-9);
        return new Experience(current + extra, Math.max(0, Math.min(.999999999, remainder)));
    }
}

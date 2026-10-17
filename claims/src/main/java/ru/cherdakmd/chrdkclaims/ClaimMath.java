package ru.cherdakmd.chrdkclaims;

/** Чистая математика приватов: рента, налоги, выкуп, лимиты. Тестируется без Bukkit. */
public final class ClaimMath {

    public static final long DAY_MILLIS = 86_400_000L;

    /** Сколько суток ренты нужно списать между paidUntil и now (0 — ещё оплачено). */
    public static long overdueDays(long paidUntil, long now) {
        if (now < paidUntil) {
            return 0;
        }
        return (now - paidUntil) / DAY_MILLIS + 1;
    }

    /** Налог на сделку между игроками (вверх, минимум 1 при цене > 0 и ненулевой ставке). */
    public static int saleTax(int price, int percent) {
        if (price <= 0 || percent <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(price * percent / 100.0));
    }

    /** Вознаграждение за обратный выкуп чанка сервером (вниз). */
    public static int sellbackReward(int chunkPrice, int percent) {
        if (chunkPrice <= 0 || percent <= 0) {
            return 0;
        }
        return (int) Math.floor(chunkPrice * percent / 100.0);
    }

    /** Фактический лимит чанков игрока. */
    public static int effectiveLimit(int base, int passBonus, boolean hasPass) {
        return base + (hasPass ? passBonus : 0);
    }

    /** Ключ чанка для карт: x в старших 32 битах, z в младших. */
    public static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private ClaimMath() {
    }
}

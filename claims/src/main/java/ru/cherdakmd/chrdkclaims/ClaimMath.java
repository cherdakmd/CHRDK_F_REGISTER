package ru.cherdakmd.chrdkclaims;

/** Чистая математика приватов: рента, налоги, выкуп, лимиты. Тестируется без Bukkit. */
public final class ClaimMath {

    public static final long DAY_MILLIS = 86_400_000L;

    /**
     * Предел «догоняющих» суток ренты за один прогон. Защищает от зависания и от
     * списания фантастических сумм, если в данных окажется испорченная дата
     * (например, {@code paidUntil = 0} после ручной правки JSON).
     */
    public static final int MAX_CATCHUP_DAYS = 400;

    /** Сколько суток ренты нужно списать между paidUntil и now (0 — ещё оплачено). */
    public static long overdueDays(long paidUntil, long now) {
        if (now < paidUntil) {
            return 0;
        }
        return (now - paidUntil) / DAY_MILLIS + 1;
    }

    /**
     * Итог обработки одного дня ренты: сколько списано и сколько суток долга осталось.
     * {@code released} — долг превысил льготный период, чанк нужно освободить.
     */
    public record RentStep(int charged, int debtDays, boolean released) {
    }

    /**
     * Обрабатывает один просроченный день ренты.
     *
     * <p>Порядок важен: сначала из казны гасится <b>старый долг</b>, затем текущий день.
     * Так игрок не получает «бесплатные» дни, если пополнил казну уже после появления
     * долга, и долг не исчезает при первом же успешном списании.</p>
     *
     * @param available  доступно в казне владельца
     * @param rent       рента за сутки
     * @param debtDays   текущий долг в сутках
     * @param graceDays  сколько суток долга терпим
     */
    public static RentStep settleRentDay(int available, int rent, int debtDays, int graceDays) {
        int debt = Math.max(0, debtDays);
        if (rent <= 0) {
            return new RentStep(0, debt, false);
        }
        int balance = Math.max(0, available);
        int charged = 0;
        // 1. Закрываем накопленный долг, пока хватает казны.
        while (debt > 0 && balance >= rent) {
            balance -= rent;
            debt--;
            charged += rent;
        }
        // 2. Проводим текущий день: если долга уже нет и денег хватает — оплачен,
        //    иначе добавляется ещё одна сутка долга.
        if (debt == 0 && balance >= rent) {
            charged += rent;
        } else {
            debt++;
        }
        return new RentStep(charged, debt, debt > graceDays);
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

    /**
     * Сколько суток осталось до следующего списания (0 — рента уже просрочена).
     * Используется в подсказках «ближайшая рента».
     */
    public static long daysUntil(long paidUntil, long now) {
        if (now >= paidUntil) {
            return 0;
        }
        return (paidUntil - now) / DAY_MILLIS;
    }

    private ClaimMath() {
    }
}

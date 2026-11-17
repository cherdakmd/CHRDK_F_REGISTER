package ru.cherdakmd.chrdkgenerator;

import java.util.UUID;

/**
 * Идемпотентные денежные выплаты генератора — чистая логика без Bukkit.
 *
 * <p>Деньги копятся в записи игрока и раз в игровой день уходят на {@code /bal}
 * через award source {@code generator-payouts}. Ключ события
 * {@code payout:<UUID>:<день>} закрепляет сумму за днём: повтор (рестарт,
 * повторный тик) не изменит уже замороженную сумму (п.31, §7). Дневной лимит
 * выдачи растёт с тиром генератора и ступенью ранга — {@link GeneratorMath#payoutDailyCap}.</p>
 */
final class GeneratorPayouts {

    /** Длительность суток, мс: дни считаются по UTC (эпоха / сутки). */
    static final long DAY_MILLIS = 86_400_000L;

    private GeneratorPayouts() {
    }

    /** Номер дня по UTC для момента времени. */
    static long epochDay(long nowMillis) {
        return Math.floorDiv(nowMillis, DAY_MILLIS);
    }

    /** Ключ события MoneyLoot: одна выплата на игрока в день. */
    static String eventKey(UUID owner, long day) {
        return "payout:" + owner + ":" + day;
    }

    /** Сумма сегодняшней выплаты: минимум из копилки и дневного лимита. */
    static long payAmount(long pendingMoney, int dailyCap) {
        return Math.max(0L, Math.min(pendingMoney, Math.max(0, dailyCap)));
    }
}

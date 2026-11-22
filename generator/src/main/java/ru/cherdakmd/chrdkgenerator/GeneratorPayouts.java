package ru.cherdakmd.chrdkgenerator;

import java.util.UUID;

/**
 * Историческая логика дневной выплаты, оставленная для распознавания старых
 * квитанций и совместимости. Новые выплаты проводит {@link GeneratorPayoutCoordinator}.
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

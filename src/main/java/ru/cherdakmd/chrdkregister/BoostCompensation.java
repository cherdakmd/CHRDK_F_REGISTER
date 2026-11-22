package ru.cherdakmd.chrdkregister;

import java.math.BigInteger;
import java.util.OptionalLong;

/** Арифметика согласованной компенсации старого XP-бустa; денег не начисляет. */
final class BoostCompensation {
    static final long MINOR_PER_HOUR = 15_000L; // 150 алмазов, 100 сотых в алмазе
    private static final BigInteger HOUR = BigInteger.valueOf(3_600_000L);
    private static final BigInteger RATE = BigInteger.valueOf(MINOR_PER_HOUR);
    private static final BigInteger LIMIT = BigInteger.valueOf(BankStore.MAX_BALANCE).multiply(BigInteger.valueOf(100));

    /** Пропорционально миллисекундам; положительный остаток округляется вверх до сотой алмаза.
     * Пустой результат означает недопустимый или не помещающийся в банк расчёт, не нулевую компенсацию. */
    static OptionalLong minor(long millis) {
        if (millis < 0) return OptionalLong.empty();
        if (millis == 0) return OptionalLong.of(0);
        BigInteger value = BigInteger.valueOf(millis).multiply(RATE).add(HOUR.subtract(BigInteger.ONE)).divide(HOUR);
        if (value.compareTo(LIMIT) > 0) return OptionalLong.empty();
        return OptionalLong.of(value.longValueExact());
    }

    private BoostCompensation() { }
}

package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoostCompensationTest {
    @Test void proportionalAndRoundedUpToBankPrecision() {
        assertEquals(0, BoostCompensation.minor(0).orElseThrow());
        assertEquals(1, BoostCompensation.minor(1).orElseThrow());
        assertEquals(1, BoostCompensation.minor(240).orElseThrow());
        assertEquals(2, BoostCompensation.minor(241).orElseThrow());
        assertEquals(15_000, BoostCompensation.minor(3_600_000).orElseThrow());
        assertEquals(22_500, BoostCompensation.minor(5_400_000).orElseThrow());
        assertEquals(1_500, BoostCompensation.minor(360_000).orElseThrow());
    }

    @Test void invalidAndBankCapacityOverflowDoNotBecomeZero() {
        assertTrue(BoostCompensation.minor(-1).isEmpty());
        assertTrue(BoostCompensation.minor(Long.MAX_VALUE).isEmpty());
        long exact = 24_000_000_000_000L; // 6 666 666,66... hours = 1 000 000 000 diamonds
        assertEquals(BankStore.MAX_BALANCE * 100, BoostCompensation.minor(exact).orElseThrow());
        assertTrue(BoostCompensation.minor(exact + 1).isEmpty());
    }
}

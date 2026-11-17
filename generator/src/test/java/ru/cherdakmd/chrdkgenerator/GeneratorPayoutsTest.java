package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Идемпотентные выплаты: дни, ключи событий, суммы под дневным лимитом. */
class GeneratorPayoutsTest {

    @Test void epochDaysAreUtcAndStable() {
        assertEquals(0, GeneratorPayouts.epochDay(0));
        assertEquals(20_000, GeneratorPayouts.epochDay(20_000L * GeneratorPayouts.DAY_MILLIS));
        // Одна и та же секунда дня — один день; следующая секунда может не сменить день.
        long day = GeneratorPayouts.epochDay(1_700_000_000_000L);
        assertEquals(day, GeneratorPayouts.epochDay(1_700_000_000_000L + 3_600_000L));
    }

    @Test void eventKeyCarriesOwnerAndDay() {
        UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals("payout:11111111-2222-3333-4444-555555555555:20000",
                GeneratorPayouts.eventKey(owner, 20_000));
    }

    @Test void payAmountRespectsDailyCapAndPending() {
        assertEquals(384, GeneratorPayouts.payAmount(10_000, 384), "копилка больше лимита — платим лимит");
        assertEquals(50, GeneratorPayouts.payAmount(50, 384), "копилка меньше лимита — платим копилку");
        assertEquals(0, GeneratorPayouts.payAmount(0, 384));
        assertEquals(0, GeneratorPayouts.payAmount(100, -5), "испорченный лимит не платится");

        int cap = GeneratorMath.payoutDailyCap(384, 64, 7, 32, 5, 4096);
        assertTrue(cap >= 384 && cap <= 4096);
        assertEquals(384 + 7 * 64 + 5 * 32, cap, "лимит растёт с тиром и ступенью ранга");
    }
}

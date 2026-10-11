package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Динамическое ценообразование: мультипликатор от запаса, округление, восстановление к эталону.
 */
class PriceEngineTest {

    @Test
    void multiplierIsOneAtReferenceStock() {
        assertEquals(1.0, PriceEngine.multiplier(1000, 1000, 0.25, 4.0), 1e-9);
    }

    @Test
    void abundanceDeflatesPrice_butNotBelowFloor() {
        assertEquals(0.5, PriceEngine.multiplier(2000, 1000, 0.25, 4.0), 1e-9);
        assertEquals(0.25, PriceEngine.multiplier(1_000_000, 1000, 0.25, 4.0), 1e-9,
                "мультипликатор не уходит ниже min");
    }

    @Test
    void scarcityInflatesPrice_butNotAboveCap() {
        assertEquals(4.0, PriceEngine.multiplier(250, 1000, 0.25, 4.0), 1e-9);
        assertEquals(4.0, PriceEngine.multiplier(1, 1000, 0.25, 4.0), 1e-9,
                "пустой запас не даёт бесконечную цену");
        assertEquals(4.0, PriceEngine.multiplier(0, 1000, 0.25, 4.0), 1e-9,
                "деление на ноль исключено (stock зажат снизу 1)");
    }

    @Test
    void priceRoundsToFourDecimals() {
        // base 0.05, множитель 0.5 = 0.025
        assertEquals(0.025, PriceEngine.price(0.05, 2000, 1000, 0.25, 4.0), 1e-9);
    }

    @Test
    void revenueRounds() {
        assertEquals(12.3457, PriceEngine.revenue(1.234567, 10), 1e-9);
    }

    @Test
    void recoveryMovesTowardReference() {
        long hour = 3_600_000L;
        long stock = PriceEngine.recover(500, 1000, 0.1, hour);
        assertEquals(550, stock, "10% от разницы 500 за час");
        long rich = PriceEngine.recover(1500, 1000, 0.1, hour);
        assertEquals(1450, rich, "возврат работает и вниз");
    }

    @Test
    void recoveryDoesNotCrossReference() {
        long stock = PriceEngine.recover(500, 1000, 1.0, 10L * 3_600_000L);
        assertEquals(1000, stock, "не перелетаем через эталон");
        long rich = PriceEngine.recover(2000, 1000, 1.0, 10L * 3_600_000L);
        assertEquals(1000, rich);
    }

    @Test
    void recoveryDisabledOrNoTimeIsNoop() {
        assertEquals(500, PriceEngine.recover(500, 1000, 0, 3_600_000L));
        assertEquals(500, PriceEngine.recover(500, 1000, 0.5, 0));
        assertTrue(PriceEngine.recover(500, 1000, 0.01, 100) >= 500, "крошечный шаг не дёргает запас");
    }
}

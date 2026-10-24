package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Стоимость телепортов: VIP-скидка, полная скидка, крайние случаи. */
class PriceCalcTest {

    @Test
    void noDiscountForRegular() {
        assertEquals(5, PriceCalc.apply(5, false, 50));
        assertEquals(5, PriceCalc.apply(5, true, 0));
    }

    @Test
    void vipDiscountRoundsDownInFavorOfPlayer() {
        assertEquals(2, PriceCalc.apply(5, true, 50), "5 - 50% = 2 (0,5 в пользу игрока)");
        assertEquals(1, PriceCalc.apply(3, true, 34));
        assertEquals(1, PriceCalc.apply(5, true, 100),
                "даже 100 % скидки оставляют минимум один алмаз");
    }

    @Test
    void degenerateCases() {
        assertEquals(0, PriceCalc.apply(0, true, 50));
        assertEquals(0, PriceCalc.apply(0, false, 50));
        assertEquals(1, PriceCalc.apply(5, true, 500), "процент зашкален — зажат к 100");
        assertEquals(1, PriceCalc.apply(1, true, 50),
                "половина от одного алмаза — всё равно один: бесплатных телепортов нет");
        assertEquals(1, PriceCalc.apply(1, false, 50), "без скидки цена не меняется");
    }
}

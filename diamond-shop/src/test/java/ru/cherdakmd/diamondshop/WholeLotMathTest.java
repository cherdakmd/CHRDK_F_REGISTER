package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Лот не должен стоить один алмаз: вместо скидки меняется количество товара. */
final class WholeLotMathTest {

    @Test
    void oneDiamondSaleDoublesTheLotInsteadOfThePrice() {
        // bid 0.01 × 128 = 1.28 → floor = 1 алмаз. Удваиваем лот до 256: 2.56 → 2.
        assertEquals(1, WholeTrades.price(0.01d, 128, false));
        assertEquals(256, WholeLotMath.fit(128, 0.01d, 0.02d, true));
        assertEquals(2, WholeTrades.price(0.01d, 256, false));
        assertTrue(WholeTrades.price(0.02d, 256, true, 1d) >= WholeLotMath.MIN_LOT_PRICE);
    }

    @Test
    void alreadyValidLotKeepsItsSize() {
        assertEquals(64, WholeLotMath.fit(64, 0.05d, 0.06d, true));
        assertEquals(8, WholeLotMath.fit(8, 0.5d, 0.6d, true));
    }

    @Test
    void sellOnlyItemsIgnoreTheBidFloor() {
        // bid ничтожный, но скупка выключена — проверяем только цену покупки.
        assertEquals(128, WholeLotMath.fit(128, 0.001d, 0.05d, false));
    }

    @Test
    void tariffRaisesTheAskWithoutShrinkingTheLot() {
        assertEquals(16, WholeLotMath.fit(16, 0.1d, 0.1d, false));
        assertEquals(8, WholeLotMath.fit(8, 0.25d, 0.25d, false));
    }

    @Test
    void invalidInputsAreRejected() {
        assertEquals(0, WholeLotMath.fit(0, 0.1d, 0.1d, true));
        assertEquals(0, WholeLotMath.fit(-5, 0.1d, 0.1d, true));
    }

    @Test
    void unitPricePerItemIsAlmostUnchangedWhenTheLotGrows() {
        double unit = 0.011875d;
        int small = 128;
        int fitted = WholeLotMath.fit(small, unit, unit * 1.2d, true);
        double pricePerItemBefore = WholeTrades.price(unit, small, false) / (double) small;
        double pricePerItemAfter = WholeTrades.price(unit, fitted, false) / (double) fitted;
        assertTrue(Math.abs(pricePerItemAfter - pricePerItemBefore) < 0.02d,
                "удвоение лота не должно менять удельную цену");
    }
}

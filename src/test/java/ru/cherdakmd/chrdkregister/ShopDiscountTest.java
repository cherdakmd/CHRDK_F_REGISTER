package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Скидка подписчикам Boosty: арифметика бонусных дней и окно подтверждения подписки. */
class ShopDiscountTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_800_000_000_000L;

    private static ShopDiscount parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return ShopDiscount.from(configuration);
    }

    /* ------------------------------ арифметика ------------------------------ */

    @Test
    void bonusRoundsUp() {
        ShopDiscount discount = ShopDiscount.of(true, 15, 30);
        assertEquals(5, discount.bonusFor(30), "15 % от 30 дней = 4.5 → 5");
        assertEquals(14, discount.bonusFor(90), "15 % от 90 дней = 13.5 → 14");
        assertEquals(2, discount.bonusFor(7), "15 % от 7 дней = 1.05 → округление вверх до 2");
    }

    @Test
    void bonusNeverExceedsPurchase() {
        ShopDiscount discount = ShopDiscount.of(true, 50, 0);
        assertEquals(1, discount.bonusFor(1), "на однодневном товаре бонус равен сроку");
        assertEquals(1, discount.bonusFor(2), "50 % от 2 дней = 1 день");
    }

    @Test
    void maxDaysCapsBonus() {
        ShopDiscount discount = ShopDiscount.of(true, 15, 5);
        assertEquals(5, discount.bonusFor(365), "потолок важнее процента");
        assertEquals(2, discount.bonusFor(7), "малый срок упирается в процент, а не в потолок");
    }

    @Test
    void zeroOrNegativeBaseGivesNothing() {
        ShopDiscount discount = ShopDiscount.of(true, 15, 30);
        assertEquals(0, discount.bonusFor(0));
        assertEquals(0, discount.bonusFor(-5));
    }

    @Test
    void disabledDiscountGivesNothing() {
        assertFalse(ShopDiscount.DISABLED.usable());
        assertEquals(0, ShopDiscount.DISABLED.bonusFor(30));
        assertFalse(ShopDiscount.of(false, 15, 30).usable(), "выключатель важнее процента");
        assertFalse(ShopDiscount.of(true, 0, 30).usable(), "нулевой процент = выключено");
    }

    @Test
    void sillyPercentIsClamped() {
        ShopDiscount discount = ShopDiscount.of(true, 500, 30);
        assertEquals(ShopDiscount.MAX_PERCENT, discount.percent());
        assertEquals(15, discount.bonusFor(30), "процент зажат до 50");
        assertEquals(45, ShopDiscount.of(true, 80, 0).bonusFor(90),
                "80 % срезается до 50 %, это 45 дней от 90");
    }

    /* ------------------------------ конфиг ------------------------------ */

    @Test
    void configDefaultsOff() throws Exception {
        assertFalse(parse("shop:\\n  enabled: true\\n").usable(),
                "без секции boosty-bonus скидка выключена");
    }

    @Test
    void readsConfig() throws Exception {
        ShopDiscount discount = parse("""
                shop:
                  boosty-bonus:
                    enabled: true
                    percent: 20
                    max-days: 10
                """);
        assertTrue(discount.usable());
        assertEquals(20, discount.percent());
        assertEquals(10, discount.maxBonusDays());
        assertEquals(6, discount.bonusFor(30), "20 % от 30 = 6");
    }

    @Test
    void brokenConfigIsClampedNotFatal() throws Exception {
        ShopDiscount discount = parse("""
                shop:
                  boosty-bonus:
                    enabled: true
                    percent: -40
                    max-days: 99999
                """);
        assertFalse(discount.usable(), "отрицательный процент = выключено");
        assertEquals(365, discount.maxBonusDays(), "потолок дней зажат сверху");
    }

    /* --------------------------- окно подтверждения --------------------------- */

    @Test
    void confirmedSubscriptionEnablesBonus() {
        assertTrue(ShopDiscount.confirmed(true, false, NOW, NOW, 48));
        assertTrue(ShopDiscount.confirmed(true, false, NOW - 47 * HOUR, NOW, 48));
    }

    @Test
    void staleConfirmationExpires() {
        assertTrue(ShopDiscount.confirmed(true, false, NOW - 48 * HOUR, NOW, 48),
                "ровно на границе окна скидка ещё жива");
        assertFalse(ShopDiscount.confirmed(true, false, NOW - 49 * HOUR, NOW, 48),
                "подписка не подтверждалась дольше окна — скидки нет");
    }

    @Test
    void neverConfirmedGivesNothing() {
        assertFalse(ShopDiscount.confirmed(true, false, 0L, NOW, 48));
    }

    @Test
    void disabledOrDryRunBeatsConfirmation() {
        assertFalse(ShopDiscount.confirmed(false, false, NOW, NOW, 48));
        assertFalse(ShopDiscount.confirmed(true, true, NOW, NOW, 48),
                "холостой прогон подписки не подтверждает");
    }

    @Test
    void graceWindowIsClampedBothWays() {
        assertTrue(ShopDiscount.confirmed(true, false, NOW, NOW, 0),
                "нулевое окно поднимается до часа");
        assertFalse(ShopDiscount.confirmed(true, false, NOW - 200 * HOUR, NOW, 10_000),
                "окно сверху ограничено 168 часами");
    }
}

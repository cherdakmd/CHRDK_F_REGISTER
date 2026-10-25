package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Арифметика сроков покупок и оформление ника — без Bukkit. */
class ShopEntitlementsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = ShopEntitlements.DAY_MILLIS;

    @Test
    void firstPurchaseStartsFromNow() {
        long until = ShopEntitlements.extend(NOW, 0L, 30, 365);
        assertEquals(NOW + 30 * DAY, until);
    }

    @Test
    void repeatedPurchaseStacks() {
        long current = NOW + 10 * DAY;
        long until = ShopEntitlements.extend(NOW, current, 30, 365);
        assertEquals(current + 30 * DAY, until, "повторная покупка добавляет дни к остатку");
    }

    @Test
    void expiredPurchaseStartsOver() {
        long expired = NOW - 5 * DAY;
        long until = ShopEntitlements.extend(NOW, expired, 30, 365);
        assertEquals(NOW + 30 * DAY, until, "истёкший срок не возвращается");
    }

    @Test
    void totalTermIsCapped() {
        long current = NOW + 360 * DAY;
        long until = ShopEntitlements.extend(NOW, current, 30, 365);
        assertEquals(NOW + 365 * DAY, until, "потолок не даёт продать «навсегда»");
    }

    @Test
    void zeroDaysDoesNotChangeUntil() {
        long current = NOW + 3 * DAY;
        assertEquals(current, ShopEntitlements.extend(NOW, current, 0, 365));
    }

    @Test
    void activeAndRemainingDays() {
        assertTrue(ShopEntitlements.active(NOW + 1, NOW));
        assertFalse(ShopEntitlements.active(NOW, NOW));
        assertFalse(ShopEntitlements.active(0L, NOW));

        assertEquals(30, ShopEntitlements.remainingDays(NOW + 30 * DAY, NOW));
        assertEquals(1, ShopEntitlements.remainingDays(NOW + 3_600_000L, NOW), "час — это ещё день");
        assertEquals(0, ShopEntitlements.remainingDays(NOW - 1, NOW));
    }

    @Test
    void decoratesPlainColor() {
        assertEquals("<gold>Notch</gold>", ShopEntitlements.decorate("Notch", "<gold>"));
    }

    @Test
    void decoratesGradient() {
        assertEquals("<gradient:#ffd700:#ff8800>Notch</gradient>",
                ShopEntitlements.decorate("Notch", "<gradient:#ffd700:#ff8800>"));
    }

    @Test
    void decoratesNestedTagsAndClosesAll() {
        assertEquals("<bold><gold>Notch</gold></bold>",
                ShopEntitlements.decorate("Notch", "<bold><gold>"),
                "все открытые теги закрываются в обратном порядке");
    }

    @Test
    void blankTagLeavesNameAsIs() {
        assertEquals("Notch", ShopEntitlements.decorate("Notch", ""));
        assertEquals("Notch", ShopEntitlements.decorate("Notch", null));
        assertEquals("Notch", ShopEntitlements.decorate("Notch", "   "));
    }

    @Test
    void brokenTagDoesNotSwallowName() {
        assertEquals("Notch", ShopEntitlements.decorate("Notch", "<gold"));
        assertEquals("Notch", ShopEntitlements.decorate("Notch", "<>"));
    }

    @Test
    void plainTextPrefixIsKept() {
        assertEquals("VIP Notch", ShopEntitlements.decorate("Notch", "VIP "));
    }
}

package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Хранение покупок каталога в data.json: сроки, потолок, отзыв и журнал продаж. */
class ShopStoreTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = ShopEntitlements.DAY_MILLIS;

    @TempDir
    Path dir;

    private RegistrationStore store() {
        return new RegistrationStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void grantedProductSurvivesReload() {
        RegistrationStore store = store();
        long until = store.grantEntitlement("Buyer", "vip-queue", NOW, 30, 365);

        assertEquals(NOW + 30 * DAY, until);
        RegistrationStore reloaded = store();
        assertEquals(until, reloaded.entitlementUntil("buyer", "vip-queue"),
                "покупка должна лежать в data.json, а не только в памяти");
    }

    @Test
    void repeatedPurchaseStacks() {
        RegistrationStore store = store();
        long first = store.grantEntitlement("Buyer", "vip-queue", NOW, 30, 365);
        long second = store.grantEntitlement("Buyer", "vip-queue", NOW, 30, 365);

        assertEquals(first + 30 * DAY, second);
    }

    @Test
    void globalCapAppliesEvenToExpensiveOrders() {
        RegistrationStore store = store();
        long until = store.grantEntitlement("Buyer", "vip-queue", NOW, 3650, 365);

        assertEquals(NOW + 365 * DAY, until, "потолок важнее заказанного срока");
    }

    @Test
    void revokeRemovesOnlyRequestedProduct() {
        RegistrationStore store = store();
        store.grantEntitlement("Buyer", "vip-queue", NOW, 30, 365);
        store.grantEntitlement("Buyer", "nick-color", NOW, 30, 365);

        assertTrue(store.revokeEntitlement("Buyer", "vip-queue"));
        assertEquals(0L, store.entitlementUntil("Buyer", "vip-queue"));
        assertTrue(store.entitlementUntil("Buyer", "nick-color") > NOW);
        assertFalse(store.revokeEntitlement("Buyer", "vip-queue"), "повторный отзыв — уже нечего снимать");
    }

    @Test
    void cleanupDropsExpiredOnly() {
        RegistrationStore store = store();
        store.grantEntitlement("Buyer", "expired", NOW - 10 * DAY, 5, 365);
        store.grantEntitlement("Buyer", "active", NOW, 30, 365);

        assertEquals(1, store.cleanupEntitlements(NOW));
        assertEquals(0L, store.entitlementUntil("Buyer", "expired"));
        assertTrue(store.entitlementUntil("Buyer", "active") > NOW);
        assertEquals(1, store.entitlementsOf("Buyer").size());
    }

    @Test
    void shopSaleGoesToJournalWithPositiveRevenueAndNegativeId() {
        RegistrationStore store = store();
        var sale = store.recordShopSale("Buyer", "vip-queue", 149, NOW);

        assertTrue(sale.id < 0, "id продажи не должен пересекаться с id донатов");
        assertEquals(RegistrationStore.DONATION_SOURCE_TRADEMC, sale.source);
        assertEquals(149_00L, sale.amountKopecks);
        assertEquals(RegistrationStore.DonationStatus.GRANTED, sale.donationStatus());
        assertEquals("Buyer", sale.grantedTo);
        assertEquals(0, sale.days, "товар не продлевает проходку — дни в журнале нулевые");
        assertTrue(sale.note.contains("vip-queue"));

        var second = store.recordShopSale("Buyer", "nick-color", 149, NOW + 1);
        assertTrue(second.id < sale.id, "каждая продажа получает свой отрицательный id");
        assertEquals(2, store.listDonations("granted", 0).size());
    }
}

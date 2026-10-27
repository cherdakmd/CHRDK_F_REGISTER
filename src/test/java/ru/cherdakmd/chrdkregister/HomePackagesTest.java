package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Оплаченные пакеты домов: количество, собственные сроки, старый формат, диск и отзыв. */
class HomePackagesTest {
    @TempDir Path dir;
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = ShopEntitlements.DAY_MILLIS;
    private RegistrationStore store() { return new RegistrationStore(dir, Logger.getAnonymousLogger()); }

    @Test void twoPurchasesAddPackagesInsteadOfExtendingFirstOne() {
        var store = store();
        assertEquals(NOW + 30 * DAY, store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365));
        assertEquals(NOW + 40 * DAY, store.grantStackedEntitlement("buyer", "homes-plus", NOW + 10 * DAY, 30, 365));
        assertEquals(2, store.entitlementCount("BUYER", "homes-plus", NOW + 29 * DAY));
        assertEquals(1, store.entitlementCount("Buyer", "homes-plus", NOW + 30 * DAY));
        assertEquals(0, store.entitlementCount("Buyer", "homes-plus", NOW + 40 * DAY));
        assertEquals(NOW + 40 * DAY, store.entitlementUntil("Buyer", "homes-plus"));
    }

    @Test void simultaneousIdenticalPaidPurchasesAreNotDeduplicated() {
        var store = store();
        for (int i = 0; i < 20; i++) store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        assertEquals(20, store.entitlementCount("Buyer", "homes-plus", NOW));
        assertEquals(0, store.entitlementCount("Buyer", "homes-plus", NOW + 30 * DAY));
    }

    @Test void packagesSurviveReloadAndCleanupDoesNotResurrectThem() {
        var store = store();
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW + 10 * DAY, 30, 365);
        store.flushBlocking();
        var loaded = store();
        assertEquals(2, loaded.entitlementCount("Buyer", "homes-plus", NOW));
        loaded.cleanupEntitlements(NOW + 30 * DAY);
        loaded.flushBlocking();
        assertEquals(1, store().entitlementCount("Buyer", "homes-plus", NOW + 30 * DAY));
        loaded.cleanupEntitlements(NOW + 40 * DAY);
        loaded.flushBlocking();
        assertEquals(0, store().entitlementCount("Buyer", "homes-plus", NOW + 40 * DAY));
        assertEquals(0, store().entitlementUntil("Buyer", "homes-plus"));
    }

    @Test void legacyPurchaseRemainsOnePackageForItsFullRemainingTerm() {
        var store = store();
        long legacyUntil = store.grantEntitlement("Buyer", "homes-plus", NOW, 60, 365);
        assertEquals(1, store.entitlementCount("Buyer", "homes-plus", NOW));
        long newUntil = store.grantStackedEntitlement("Buyer", "homes-plus", NOW + DAY, 30, 365);
        assertEquals(NOW + 31 * DAY, newUntil, "Сообщение показывает срок новой покупки");
        assertEquals(legacyUntil, store.entitlementUntil("Buyer", "homes-plus"), "LP/витрина видят последний срок");
        assertEquals(2, store.entitlementCount("Buyer", "homes-plus", NOW + DAY));
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW + 2 * DAY, 30, 365);
        assertEquals(3, store.entitlementCount("Buyer", "homes-plus", NOW + 2 * DAY), "Legacy не копируется повторно");
        assertEquals(1, store.entitlementCount("Buyer", "homes-plus", NOW + 32 * DAY));
        assertEquals(0, store.entitlementCount("Buyer", "homes-plus", legacyUntil));
    }

    @Test void expiredLegacyIsNotConvertedIntoAnExtraPaidPackage() {
        var store = store();
        store.grantEntitlement("Buyer", "homes-plus", NOW - 40 * DAY, 30, 365);
        assertEquals(0, store.entitlementCount("Buyer", "homes-plus", NOW));
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        assertEquals(1, store.entitlementCount("Buyer", "homes-plus", NOW));
    }

    @Test void revokeRemovesAllPackagesButDoesNotTouchOtherProducts() {
        var store = store();
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        store.grantEntitlement("Buyer", "trail-flame", NOW, 30, 365);
        assertTrue(store.revokeEntitlement("Buyer", "homes-plus"));
        assertEquals(0, store.entitlementCount("Buyer", "homes-plus", NOW));
        assertEquals(0, store.entitlementUntil("Buyer", "homes-plus"));
        assertEquals(1, store.entitlementCount("Buyer", "trail-flame", NOW));
        assertFalse(store.revokeEntitlement("Buyer", "homes-plus"));
        store.flushBlocking();
        assertEquals(0, store().entitlementCount("Buyer", "homes-plus", NOW));
    }

    @Test void invalidPurchaseAndUnknownAccountDoNotGrantAnything() {
        var store = store();
        assertEquals(0, store.grantStackedEntitlement("", "homes-plus", NOW, 30, 365));
        assertEquals(0, store.grantStackedEntitlement("Buyer", "", NOW, 30, 365));
        assertEquals(0, store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 0, 365));
        assertEquals(0, store.entitlementCount(null, "homes-plus", NOW));
        assertEquals(0, store.entitlementCount("Nobody", "homes-plus", NOW));
        assertTrue(store.getAccount("Buyer").isEmpty());
    }

    @Test void snapshotsDoNotShareMutablePackageLists() {
        var store = store();
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 30, 365);
        var snapshot = store.getAccount("Buyer").orElseThrow();
        snapshot.entitlementPackages.get("homes-plus").clear();
        snapshot.entitlementPackages.clear();
        assertEquals(1, store.entitlementCount("Buyer", "homes-plus", NOW));
        store.flushBlocking();
        assertEquals(1, store().entitlementCount("Buyer", "homes-plus", NOW));
    }

    @Test void commandPathCountsPaidPackagesAndLeavesExistingHomesAccessible() throws Exception {
        String handler = java.nio.file.Files.readString(Path.of(
                "teleports/src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java"));
        int start = handler.indexOf("private int extraHomes(");
        int end = handler.indexOf("    /** Ступень", start);
        String method = handler.substring(start, end);
        assertTrue(method.contains("entitlementCount(player.getName(), \"homes-plus\")"));
        assertFalse(method.contains("hasPermission("));
        assertTrue(handler.contains("homeLimit(rank(player), extraHomes(player))"));
        assertTrue(handler.contains("if (!exists && store.countPlayerHomes(player.getUniqueId()) >= max)"));
        String shop = java.nio.file.Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdkregister/ShopService.java"));
        assertTrue(shop.contains("\"homes-plus\".equals(product.id())"));
        assertTrue(shop.contains("grantStackedEntitlement(playerName, product.id()"));
    }

    @Test void perPackageDurationCapDoesNotCapPackageQuantity() {
        var store = store();
        assertEquals(NOW + 365 * DAY, store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 400, 365));
        store.grantStackedEntitlement("Buyer", "homes-plus", NOW, 400, 365);
        assertEquals(2, store.entitlementCount("Buyer", "homes-plus", NOW));
        assertEquals(NOW + 365 * DAY, store.entitlementUntil("Buyer", "homes-plus"));
    }
}

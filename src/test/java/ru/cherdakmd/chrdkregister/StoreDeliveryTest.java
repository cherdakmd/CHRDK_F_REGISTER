package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StoreDeliveryTest {
    @TempDir Path dir;
    private RegistrationStore store() { return new RegistrationStore(dir, Logger.getAnonymousLogger()); }

    @Test void onlyRealRconOfEnabledStoreMayOmitReceipt() {
        assertEquals(PaymentConfirmation.Mode.STORE_DELIVERY, PaymentConfirmation.mode(true, false, true, ""));
        assertEquals(PaymentConfirmation.Mode.STORE_DELIVERY, PaymentConfirmation.mode(true, true, true, ""));
        assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(true, false, false, ""));
        assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(false, true, true, ""));
        assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(false, true, false, ""));
        assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(false, false, true, ""));
        assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(false, false, true, "trademc:123"));
    }

    @Test void explicitReceiptStillWorksButInvalidReceiptNeverBecomesAnonymousDelivery() {
        assertEquals(PaymentConfirmation.Mode.RECEIPT, PaymentConfirmation.mode(false, true, true, "provider:123"));
        assertEquals(PaymentConfirmation.Mode.RECEIPT, PaymentConfirmation.mode(true, false, false, "provider:123"));
        for (String invalid : new String[] {null, "bad!", "../invalid", "x"}) {
            assertEquals(PaymentConfirmation.Mode.DENIED, PaymentConfirmation.mode(true, false, true, invalid));
        }
        assertEquals("", PaymentConfirmation.reference(new String[] {"shoppay", "Nick", "99"}));
        assertArrayEquals(new String[] {"shopgift", "Nick", "product", "Giver"},
                PaymentConfirmation.arguments(new String[] {"shopgift", "Nick", "product", "Giver"}));
    }

    @Test void deliveryJournalIsDurableSeparateFromReceiptsAndDoesNotCreatePaidRights() {
        var store = store();
        String first = store.reserveStoreDelivery("Rcon", "shopbuy Nick product", 1000);
        assertFalse(first.isEmpty());
        var loaded = store();
        assertTrue(loaded.storeDeliveries().get(first).contains("RECEIVED_RECONCILE_IF_INTERRUPTED"));
        assertTrue(loaded.paymentReceipts().isEmpty(), "local delivery id must not masquerade as payment evidence");
        assertTrue(loaded.getAccount("Nick").isEmpty(), "reservation itself cannot grant a product/rank");
        loaded.finishStoreDelivery(first, "GRANTED");
        assertTrue(store().storeDeliveries().get(first).contains("GRANTED"));
    }

    @Test void identicalLegitimatePurchasesAreNotSuppressedByHeuristicDeduplication() {
        var store = store();
        String first = store.reserveStoreDelivery("Rcon", "shopbuy Nick product", 1000);
        String second = store.reserveStoreDelivery("Rcon", "shopbuy Nick product", 1000);
        assertNotEquals(first, second);
        assertEquals(2, store().storeDeliveries().size());
        assertTrue(store().paymentReceipts().isEmpty());
    }

    @Test void diskFailureCannotAuthorizeDelivery() throws Exception {
        Path blocked = dir.resolve("not-a-folder");
        Files.writeString(blocked, "file");
        var store = new RegistrationStore(blocked, Logger.getAnonymousLogger());
        assertEquals("", store.reserveStoreDelivery("Rcon", "shoppay Nick 99", 1000));
    }

    @Test void auditDoesNotConfuseRconDeliveriesWithVerifiedPaymentReceipts() {
        var store = store();
        String id = store.reserveStoreDelivery("Rcon", "shoppay Nick 99", 1000);
        String report = PaidAuditReport.render(store.getAllAccounts(), store.paymentReceipts(), store.storeDeliveries(), 1000);
        assertTrue(report.contains("storeRconDeliveriesNotPaymentReceipts"));
        assertTrue(report.contains(id));
        assertTrue(store.paymentReceipts().isEmpty());
    }

    @Test void stockMandatoryReceiptTextMigratesButOwnerChangesRemainUntouched() {
        var defaults = Map.of("paid-only.required", "RCON without receipt; manual console with receipt",
                "usage.pass-shoppay", "shoppay Nick amount [payment=id]");
        var legacy = Map.of("paid-only.required", "always require receipt", "usage.pass-shoppay", "old stock usage");
        var overrides = new HashMap<>(Map.of("paid-only.required", "always require receipt", "usage.pass-shoppay", "custom owner usage"));
        Messages.migrateStockOverrides(defaults, overrides, legacy);
        assertEquals(defaults.get("paid-only.required"), overrides.get("paid-only.required"));
        assertEquals("custom owner usage", overrides.get("usage.pass-shoppay"));
    }
}

package ru.cherdakmd.chrdkregister;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AuctionPayment;
import ru.cherdakmd.chrdkregister.api.BankCharge;
import static org.junit.jupiter.api.Assertions.*;

class FishingSnapshotChargeGuardTest {
    @TempDir Path dir;
    final UUID lineage = UUID.randomUUID(), owner = UUID.randomUUID(), tx = UUID.randomUUID();
    final String original = "original-native-snapshot";
    BankCharge payment(String source, String snapshot, long debit, long cash) {
        return new BankCharge(source, lineage, 1, tx, owner, 100, debit, cash, 0,
                AuctionPayment.hash(snapshot, snapshot), "a".repeat(64), true, 10000);
    }
    @Test void onlyFishingBankOnlyReceiptsWithExactStoredSnapshotAreAllowed() {
        for (String source : new String[]{"fishing-upgrades", "fishing-bait", "fishing-horizons-rod", "fishing-horizons-bait"}) {
            var p = payment(source, original, 100, 0);
            assertTrue(FishingSnapshotChargeGuard.valid(p, original));
            // A round-trip may be semantically equal but its bytes are different; it must not replace the recorded hash.
            assertFalse(FishingSnapshotChargeGuard.valid(p, "equivalent-reserialized-snapshot"));
            assertFalse(FishingSnapshotChargeGuard.valid(p, null));
            assertFalse(FishingSnapshotChargeGuard.valid(p, "x".repeat(4 * 1024 * 1024 + 1)));
        }
        assertFalse(FishingSnapshotChargeGuard.valid(payment("teleports", original, 100, 0), original));
        assertFalse(FishingSnapshotChargeGuard.valid(payment("fishing-horizons-bait", original, 50, 50), original));
    }
    @Test void semanticallyEqualNonCanonicalSnapshotKeepsOriginalReceiptHash() {
        String snapshot = "[" + " null ,".repeat(35) + " null ]";
        var current = new org.bukkit.inventory.ItemStack[36];
        assertTrue(ru.cherdakmd.chrdkregister.api.ChargeInventory.matches(current, snapshot));
        String roundTrip = ru.cherdakmd.chrdkregister.api.ChargeInventory.encode(
                ru.cherdakmd.chrdkregister.api.ChargeInventory.decode(snapshot));
        assertNotEquals(snapshot, roundTrip);
        var pending = payment("fishing-horizons-bait", snapshot, 100, 0);
        assertTrue(FishingSnapshotChargeGuard.valid(pending, snapshot));
        assertFalse(FishingSnapshotChargeGuard.valid(pending, roundTrip));
    }
    @Test void productionPathRechecksIdentityAuthorizationPermissionInventoryAndSpendableBalance() throws Exception {
        String source = java.nio.file.Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        String method = source.substring(source.indexOf("public boolean commitFishingSnapshotCharge"), source.indexOf("@Override public long spendableBankBalance("));
        for (String guard : new String[]{"auctionBankReady()", "FishingSnapshotChargeGuard.valid", "payer.isOnline()", "payer.isDead()",
                "payer.getUniqueId().equals(payment.payer())", "payer.hasMetadata(\"NPC\")", "isAuthenticated(payer)",
                "GameMode.SURVIVAL", "GameMode.ADVENTURE", "payer.hasPermission(permission)", "ChargeInventory.matches(current, snapshot)",
                "ChargeInventory.planMinorBankOnly", "spendableBalanceMinor", "plan.debit()", "plan.cash()", "plan.change()"}) {
            assertTrue(method.contains(guard), guard);
            assertTrue(method.indexOf(guard) < method.indexOf("bank.settleCharge(payment)"), guard);
        }
        assertFalse(method.contains("setStorageContents"));
    }
    @Test void oldAndNewPathShareTheSameDurablePaymentIdentityAndReplayOnce() {
        var bank = new BankStore(dir, Logger.getAnonymousLogger()); bank.load();
        assertTrue(bank.registerIncomeSource("fishing-horizons-bait", lineage));
        assertEquals(2, bank.deposit(owner, "player", 2)); bank.saveNow();
        var pending = payment("fishing-horizons-bait", original, 100, 0);
        assertEquals(BankCharge.Status.MISSING, bank.bankChargeStatus(pending));
        assertTrue(FishingSnapshotChargeGuard.valid(pending, original));
        assertTrue(bank.settleCharge(pending)); assertTrue(bank.settleCharge(pending));
        var restarted = new BankStore(dir, Logger.getAnonymousLogger()); restarted.load();
        assertEquals(100, restarted.balanceMinor(owner));
        assertEquals(BankCharge.Status.COMMITTED, restarted.bankChargeStatus(pending));
    }
}

package ru.cherdakmd.chrdkregister;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.BankCharge;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary bank.json; does not emulate a live Purpur server or filesystem crash. */
class FishingGenerationBankTest {
    @TempDir Path dir;
    BankStore load() { var bank = new BankStore(dir, Logger.getAnonymousLogger()); bank.load(); return bank; }
    @Test void registeringFreshFishingSourcesPreservesOldReceiptsBalanceAndSequence() {
        var bank = load(); var owner = UUID.randomUUID(); var oldRod = UUID.randomUUID(); var newRod = UUID.randomUUID();
        var oldSale = UUID.randomUUID(); var newSale = UUID.randomUUID(); var oldAlbum = UUID.randomUUID();
        assertTrue(bank.registerIncomeSource("fishing-upgrades", oldRod));
        assertTrue(bank.registerIncomeSource("fishing-sale", oldSale));
        assertTrue(bank.registerIncomeSource("fishing-album", oldAlbum));
        assertEquals(100, bank.deposit(owner, "player", 100)); bank.saveNow();
        var hash = "a".repeat(64);
        var prior = new BankCharge("fishing-upgrades", oldRod, 1, UUID.randomUUID(), owner,
                100, 100, 0, 0, hash, hash, true, 10000);
        assertTrue(bank.settleCharge(prior));
        var id = UUID.randomUUID().toString();
        assertTrue(bank.creditEntitlementMinor("fishing-sale", oldSale, id, owner, 200));
        assertTrue(bank.creditEntitlementMinor("fishing-album", oldAlbum, owner.toString(), owner, 150));
        long before = bank.balanceMinor(owner);
        assertTrue(bank.registerIncomeSource("fishing-horizons-rod", newRod));
        assertTrue(bank.registerIncomeSource("fishing-horizons-bait", UUID.randomUUID()));
        assertTrue(bank.registerIncomeSource("fishing-horizons-sale", newSale));
        bank = load();
        assertEquals(before, bank.balanceMinor(owner));
        assertEquals(1, bank.bankChargeSequence("fishing-upgrades", oldRod));
        assertEquals(0, bank.bankChargeSequence("fishing-horizons-rod", newRod));
        assertEquals(BankCharge.Status.COMMITTED, bank.bankChargeStatus(prior));
        assertEquals(200, bank.creditedEntitlement("fishing-sale", oldSale, id, owner));
        assertEquals(150, bank.creditedEntitlement("fishing-album", oldAlbum, owner.toString(), owner));
        assertEquals(0, bank.creditedEntitlement("fishing-horizons-sale", newSale, id, owner));
        var next = new BankCharge("fishing-horizons-rod", newRod, 1, UUID.randomUUID(), owner,
                100, 100, 0, 0, hash, hash, true, 10000);
        assertTrue(bank.settleCharge(next)); assertTrue(bank.settleCharge(next));
        bank = load(); assertEquals(before - 100, bank.balanceMinor(owner));
        assertEquals(BankCharge.Status.COMMITTED, bank.bankChargeStatus(prior));
        assertEquals(BankCharge.Status.COMMITTED, bank.bankChargeStatus(next));
        assertEquals(200, bank.creditedEntitlement("fishing-sale", oldSale, id, owner));
        assertEquals(150, bank.creditedEntitlement("fishing-album", oldAlbum, owner.toString(), owner));
    }
}

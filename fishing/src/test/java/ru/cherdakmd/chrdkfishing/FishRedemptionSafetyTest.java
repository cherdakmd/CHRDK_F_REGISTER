package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory bank/playerdata models and a real temporary SQLite journal, not a server crash test. */
class FishRedemptionSafetyTest {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID(), otherOwner = UUID.randomUUID();
    final byte[] key = new byte[32];
    Path file() { return dir.resolve("fish-redemption.db"); }
    FishToken.Token fish() {
        return FishToken.issue(key, UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 100);
    }
    @Test void preparedCookingReservesIdentityAgainstSaleAcrossPlayersAndRestarts() throws Exception {
        var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag(); var fish = fish();
        var cooking = UUID.randomUUID();
        try (var journal = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareCook(owner, cooking, "before", "after", fish, key));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(otherOwner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareCook(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
            assertTrue(bank.receipts(otherOwner).isEmpty());
        }
        bag.failAfterApply = true;
        try (var journal = new FishSaleJournal(file(), false)) {
            assertThrows(IOException.class, () -> journal.recoverCook(owner, cooking, bag));
            assertEquals(cooking, journal.pendingCook(owner));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(otherOwner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
        }
        try (var journal = new FishSaleJournal(file(), false)) {
            assertEquals(FishSaleJournal.Result.DONE, journal.recoverCook(owner, cooking, bag));
            assertEquals(FishSaleJournal.Result.DONE, journal.recoverCook(owner, cooking, bag));
            assertEquals(1, bag.removals); assertNull(journal.pendingCook(owner));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(otherOwner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
        }
    }
    @Test void saleReservationBlocksCookingAcrossOwnersEvenBeforePayout() throws Exception {
        var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag(); var fish = fish();
        var sale = UUID.randomUUID(); bag.available = false;
        try (var journal = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepare(owner, sale, "before", "after", List.of(fish), key, bank));
            assertEquals(FishSaleJournal.Result.WAIT, journal.recover(owner, sale, bag, bank));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareCook(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(otherOwner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
            assertTrue(bank.receipts(owner).isEmpty());
        }
        try (var journal = new FishSaleJournal(file(), false)) {
            bag.available = true;
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, sale, bag, bank));
            assertEquals(1, bag.removals); assertEquals(100L, bank.receipts(owner).get(FishSaleJournal.receiptKey(sale)));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareCook(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
        }
    }
    @Test void orderReservationPreventsSaleAndCookingOfSameFishAndDuplicateOrder() throws Exception {
        var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag();
        var order = FishingOrders.forDay(FishingOrders.utcDay()).get(0);
        var species = FishCatalog.byId(order.speciesId());
        var caught = new FishingModel.Catch(species.id(), order.size(), species.lengths().get(order.size().ordinal()).min());
        var fish = FishToken.issue(key, UUID.randomUUID(), caught, 1);
        var tx = UUID.randomUUID();
        try (var journal = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareOrder(owner, tx, "before", "after", fish, key, bank, order));
            assertTrue(journal.orderClaimed(owner, order.day(), order.slot()));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(otherOwner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareCook(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, tx, bag, bank));
            assertEquals(order.rewardMinor(), bank.receipts(owner).get(FishSaleJournal.receiptKey(tx)));
        }
        try (var journal = new FishSaleJournal(file(), false)) {
            var another = FishToken.issue(key, UUID.randomUUID(), caught, 1);
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareOrder(owner, UUID.randomUUID(), "before", "after", another, key, bank, order));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareCook(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
        }
    }
    @Test void findIsGrantedOnceButDoesNotConsumeFish() throws Exception {
        var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag(); var fish = fish();
        var find = UUID.randomUUID();
        try (var journal = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareFind(owner, find, "before", "after", fish, key));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareFind(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
            assertEquals(FishSaleJournal.Result.DONE, journal.recoverFind(owner, find, bag));
            assertEquals(FishSaleJournal.Result.DONE, journal.recoverFind(owner, find, bag));
            assertEquals(1, bag.removals);
        }
        try (var journal = new FishSaleJournal(file(), false)) {
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareFind(otherOwner, UUID.randomUUID(), "before", "after", fish, key));
            // Find rewards do not consume or change the frozen sale price.
            var sale = UUID.randomUUID(); bag.state = "before";
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepare(owner, sale, "before", "after", List.of(fish), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, sale, bag, bank));
            assertEquals(100L, bank.receipts(owner).get(FishSaleJournal.receiptKey(sale)));
        }
    }
    @Test void unexpectedReceiptForAnotherOwnerFailsSourceWideReconciliation() throws Exception {
        var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag(); var sale = UUID.randomUUID();
        try (var journal = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepare(owner, sale, "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, sale, bag, bank));
            assertTrue(journal.historyMatchesAll(Map.of(owner, bank.receipts(owner))));
            assertFalse(journal.historyMatchesAll(Map.of(owner, bank.receipts(owner), otherOwner,
                    Map.of(FishSaleJournal.receiptKey(UUID.randomUUID()), 50L))));
            assertFalse(journal.historyMatchesAll(null));
            assertFalse(journal.historyMatchesAll(Map.of()));
        }
    }
}

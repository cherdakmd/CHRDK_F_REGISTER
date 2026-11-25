package ru.cherdakmd.chrdkfishing;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FishingOrdersTest {
    @TempDir Path dir;
    @Test void allDailyOrdersStayWithinBankLimitAndIncludePremiumCatalog() {
        var seen = new java.util.HashSet<String>();
        for (long day = 1; day <= FishCatalog.all().size(); day++) {
            var orders = FishingOrders.forDay(day); assertEquals(3, orders.size());
            for (var order : orders) {
                seen.add(order.speciesId());
                assertTrue(order.rewardMinor() > 0 && order.rewardMinor() <= FishToken.MAX_PRICE_POINTS);
            }
        }
        assertTrue(seen.containsAll(FishCatalog.premium().stream().map(FishCatalog.Premium::id).toList()));
    }
    @Test void dailyOrdersAreStableAndValidateFishAndReservation() throws Exception {
        long day = FishingOrders.utcDay(); var orders = FishingOrders.forDay(day);
        assertEquals(3, orders.size()); assertEquals(orders, FishingOrders.forDay(day));
        var order = orders.get(0); var species = FishCatalog.byId(order.speciesId());
        var fish = new FishingModel.Catch(species.id(), order.size(), species.lengths().get(order.size().ordinal()).min());
        byte[] key = new byte[32]; var token = FishToken.issue(key, UUID.randomUUID(), fish, FishingModel.price(fish));
        assertTrue(order.matches(token)); assertTrue(order.rewardMinor() > token.quotedPrice());
        var owner = UUID.randomUUID(); var bank = new FishSaleJournalTest.Bank(); var bag = new FishSaleJournalTest.Bag();
        try (var journal = new FishSaleJournal(dir.resolve("sales.db"), true)) {
            var tx = UUID.randomUUID();
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareOrder(owner, tx, "before", "after", token, key, bank, order));
            assertTrue(journal.orderClaimed(owner, day, order.slot()));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, tx, bag, bank));
            assertEquals(order.rewardMinor(), bank.receipts(owner).get(FishSaleJournal.receiptKey(tx)));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(owner, UUID.randomUUID(), "before", "after", List.of(token), key, bank));
            var another = FishToken.issue(key, UUID.randomUUID(), fish, FishingModel.price(fish));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepareOrder(owner, UUID.randomUUID(), "before", "after", another, key, bank, order));
        }
    }
}

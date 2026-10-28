package ru.cherdakmd.chrdklottery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class LotteryStoreTest {
    @TempDir Path dir;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private LotteryStore store(Path folder) throws IOException {
        LotteryStore store = new LotteryStore(folder, Logger.getAnonymousLogger()); store.load(); return store;
    }
    @Test void persistsRealDepositsAndUuidNotNickname() throws Exception {
        var s = store(dir);
        s.purchase(alice, "Alice", 3, 6);
        s.purchase(alice, "NewName", 2, 20); // changed price and name must not revalue old tickets
        var loaded = store(dir);
        assertEquals(5, loaded.ticketsOf(alice)); assertEquals(26, loaded.pot());
        assertEquals("NewName", loaded.displayName(alice.toString()));
        assertThrows(UnsupportedOperationException.class, () -> loaded.ticketsSnapshot().clear());
    }
    @Test void settlesOnceAndPartiallyClaimsPrizeAfterRestart() throws Exception {
        var s = store(dir); s.markDrawnNow(100);
        s.purchase(alice, "Alice", 10, 100);
        var result = s.draw(200, 80, new Random(0));
        assertEquals("Alice", result.playerName()); assertEquals(80, result.payout());
        assertEquals(0, s.pot()); assertTrue(s.ticketsSnapshot().isEmpty());
        var loaded = store(dir); assertEquals(80, loaded.pendingPayoutOf(alice));
        assertEquals(result, loaded.lastWinner());
        assertThrows(IllegalArgumentException.class, () -> loaded.draw(200, 80, new Random()));
        loaded.debitPrize(alice, 64); assertEquals(16, store(dir).pendingPayoutOf(alice));
        loaded.debitPrize(alice, 16); assertEquals(0, store(dir).pendingPayoutOf(alice));
        assertThrows(IllegalArgumentException.class, () -> loaded.debitPrize(alice, 1));
    }
    @Test void skippingBoundaryKeepsPrepaidRound() throws Exception {
        var s = store(dir); s.purchase(alice, "Alice", 5, 10); s.markDrawnNow(300);
        assertEquals(10, store(dir).pot()); assertEquals(5, store(dir).ticketsOf(alice));
        assertEquals(300, store(dir).lastDrawAt());
    }
    @Test void emptyDrawDoesNotEraseLastWinner() throws Exception {
        var s = store(dir); s.purchase(alice, "Alice", 1, 2);
        var last = s.draw(100, 80, new Random());
        assertNull(s.draw(200, 80, new Random()));
        assertEquals(last, store(dir).lastWinner());
    }
    @Test void corruptLedgerFailsClosedAndRemainsUntouched() throws Exception {
        for (String content : List.of("not json", "null", "{}", "{\"schema\":999}")) {
            Files.writeString(dir.resolve("data.json"), content);
            assertThrows(IOException.class, () -> store(dir));
            assertEquals(content, Files.readString(dir.resolve("data.json")));
        }
    }
    @Test void failedPersistenceDoesNotPublishPurchaseDrawOrDebit() throws Exception {
        var s = store(dir); s.purchase(alice, "Alice", 2, 10);
        Files.createDirectory(dir.resolve("data.json.tmp")); // force writer failure
        assertThrows(IOException.class, () -> s.purchase(alice, "Alice", 1, 2));
        assertEquals(2, s.ticketsOf(alice)); assertEquals(10, s.pot());
        assertThrows(IOException.class, () -> s.draw(100, 80, new Random()));
        assertEquals(0, s.lastDrawAt()); assertEquals(0, s.pendingPayoutOf(alice));
        Files.delete(dir.resolve("data.json.tmp"));
        var restarted = store(dir); restarted.draw(100, 80, new Random());
        Files.createDirectory(dir.resolve("data.json.tmp"));
        assertThrows(IOException.class, () -> restarted.debitPrize(alice, 1));
        assertEquals(8, restarted.pendingPayoutOf(alice));
    }
    @Test void ledgerLimitsRejectBeforeMutatingMemoryOrDisk() throws Exception {
        var s = store(dir); s.purchase(alice, "Alice", 1, LotteryStore.MAX_POT);
        assertThrows(IllegalArgumentException.class, () -> s.purchase(alice, "Alice", 1, 1));
        assertEquals(LotteryStore.MAX_POT, store(dir).pot()); assertEquals(1, s.ticketsOf(alice));
        assertThrows(IllegalArgumentException.class, () -> s.purchase(alice, "Alice", 0, 1));
    }
}

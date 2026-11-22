package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BoostMigrationRunnerTest {
    @TempDir Path dir;
    private static final Logger LOG = Logger.getAnonymousLogger();
    @Test void offlineAccountsAndLaterLegacyGrantsAreProcessedAutomaticallyWithoutRepeats() {
        var store = new RegistrationStore(dir, LOG);
        var bank = new BankStore(dir, LOG); bank.load();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        store.recordPlayerSeen(first, "First"); store.grantRewardBoost("First", 1, 0);
        store.recordPlayerSeen(second, "Second"); store.grantRewardBoost("Second", 2, 0);
        var runner = new BoostMigrationRunner(store, bank, LOG);
        runner.tick(); runner.tick(); runner.tick();
        assertEquals(15_000, bank.balanceMinor(first)); assertEquals(30_000, bank.balanceMinor(second));
        assertEquals(0, store.rewardBoostRemaining("First")); assertEquals(0, store.rewardBoostRemaining("Second"));
        store.grantRewardBoost("First", 1, 0);
        runner.tick(); runner.tick(); runner.tick();
        assertEquals(30_000, bank.balanceMinor(first)); assertEquals(30_000, bank.balanceMinor(second));
    }
    @Test void fullAccountDoesNotStarveOtherAccountsAndRetainsItsBoost() {
        var store = new RegistrationStore(dir, LOG); var bank = new BankStore(dir, LOG); bank.load();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        store.recordPlayerSeen(first, "First"); store.grantRewardBoost("First", 1, 0);
        store.recordPlayerSeen(second, "Second"); store.grantRewardBoost("Second", 1, 0);
        bank.deposit(first, "First", BankStore.MAX_BALANCE);
        var runner = new BoostMigrationRunner(store, bank, LOG);
        runner.tick(); runner.tick();
        assertEquals(3_600_000, store.rewardBoostRemaining("First"));
        assertEquals(15_000, bank.balanceMinor(second));
        assertTrue(bank.withdraw(first, 150)); runner.tick(); runner.tick();
        assertEquals(0, store.rewardBoostRemaining("First"));
        assertEquals(BankStore.MAX_BALANCE * 100L, bank.balanceMinor(first));
    }
}

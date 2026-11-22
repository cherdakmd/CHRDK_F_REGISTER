package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.RegistrationStore.BoostSettlement.*;

class BoostCompensationRecoveryTest {
    @TempDir Path dir;
    private static final Logger LOG = Logger.getAnonymousLogger();
    private RegistrationStore account(UUID id) {
        var s = new RegistrationStore(dir, LOG); s.recordPlayerSeen(id, "Steve"); s.grantRewardBoost("Steve", 1, 0); return s;
    }
    private BankStore bank() { var b = new BankStore(dir, LOG); b.load(); return b; }

    @Test void settlementPersistsBothHalvesAndDoesNotRepeat() {
        UUID id = UUID.randomUUID(); var s = account(id); var b = bank();
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(0, s.rewardBoostRemaining("Steve")); assertEquals(15_000, b.balanceMinor(id));
        s = new RegistrationStore(dir, LOG); b = bank();
        assertEquals(NONE, s.settleBoostCompensation("Steve", b)); assertEquals(15_000, b.balanceMinor(id));
    }

    @Test void noCreditUntilIntentCheckpointSucceeds() throws Exception {
        UUID id = UUID.randomUUID(); var s = account(id); var b = bank();
        Files.createDirectory(dir.resolve("data.json.reward.tmp"));
        assertEquals(WAIT, s.settleBoostCompensation("Steve", b));
        assertEquals(0, b.balanceMinor(id));
        s.consumeRewardBoost("Steve", 1000); // frozen while intent awaits durable checkpoint
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve"));
        Files.delete(dir.resolve("data.json.reward.tmp"));
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(15_000, b.balanceMinor(id));
    }

    @Test void restartAfterAmbiguousBankReplaceUsesSameReceipt() {
        UUID id = UUID.randomUUID(); var s = account(id);
        var b = new BankStore(dir, LOG, () -> { throw new IOException("after bank replace"); }); b.load();
        assertEquals(WAIT, s.settleBoostCompensation("Steve", b));
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve"));
        s = new RegistrationStore(dir, LOG); b = bank();
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(0, s.rewardBoostRemaining("Steve")); assertEquals(15_000, b.balanceMinor(id));
        assertEquals(NONE, s.settleBoostCompensation("Steve", b));
        assertEquals(15_000, b.balanceMinor(id));
    }

    @Test void restartAfterBankCommitAndFailedSourceCheckpointDoesNotDoubleCredit() throws Exception {
        UUID id = UUID.randomUUID(); var s = account(id);
        var b = new BankStore(dir, LOG, () -> Files.createDirectory(dir.resolve("data.json.reward.tmp"))); b.load();
        assertEquals(WAIT, s.settleBoostCompensation("Steve", b));
        assertEquals(15_000, b.balanceMinor(id));
        Files.delete(dir.resolve("data.json.reward.tmp"));
        s = new RegistrationStore(dir, LOG); b = bank();
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve"));
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(0, s.rewardBoostRemaining("Steve")); assertEquals(15_000, b.balanceMinor(id));
    }

    @Test void oldPendingGrantDuringFrozenIntentIsNotLost() {
        UUID id = UUID.randomUUID(); var s = account(id); var b = bank(); b.quarantineCash();
        assertEquals(WAIT, s.settleBoostCompensation("Steve", b));
        s.grantRewardBoost("Steve", 1, 0);
        b = bank();
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve")); assertEquals(15_000, b.balanceMinor(id));
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(0, s.rewardBoostRemaining("Steve")); assertEquals(30_000, b.balanceMinor(id));
    }

    @Test void fullBankKeepsFrozenBoostAndPaysAfterSpaceIsAvailable() {
        UUID id = UUID.randomUUID(); var s = account(id); var b = bank();
        assertEquals(BankStore.MAX_BALANCE, b.deposit(id, "Steve", BankStore.MAX_BALANCE));
        assertEquals(WAIT, s.settleBoostCompensation("Steve", b));
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve"));
        assertTrue(b.withdraw(id, 150));
        assertEquals(COMPLETE, s.settleBoostCompensation("Steve", b));
        assertEquals(0, s.rewardBoostRemaining("Steve"));
        assertEquals(BankStore.MAX_BALANCE * 100L, b.balanceMinor(id));
    }

    @Test void rejectDuplicateIdentityAndPreserveExistingBoost() {
        UUID id = UUID.randomUUID(); var s = account(id); s.recordPlayerSeen(id, "Other"); var b = bank();
        assertEquals(CONFLICT, s.settleBoostCompensation("Steve", b));
        assertEquals(3_600_000, s.rewardBoostRemaining("Steve")); assertEquals(0, b.balanceMinor(id));
    }
}

package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.MoneyGrant.*;

class BoostCompensationBankTest {
    @TempDir Path dir;
    private BankStore bank() { var b = new BankStore(dir, Logger.getAnonymousLogger()); b.load(); return b; }

    @Test void receiptAndExactFractionSurviveRestartWithoutDoubleCredit() {
        var owner = UUID.randomUUID(); var transaction = UUID.randomUUID();
        var b = bank();
        assertEquals(ADDED, b.creditBoostCompensationOnce(owner, transaction, 22_500));
        assertEquals(22_500, b.balanceMinor(owner));
        b = bank();
        assertTrue(b.available());
        assertEquals(ALREADY, b.creditBoostCompensationOnce(owner, transaction, 22_500));
        assertEquals(FAILED, b.creditBoostCompensationOnce(owner, transaction, 22_501));
        assertEquals(FAILED, b.creditBoostCompensationOnce(UUID.randomUUID(), transaction, 22_500));
        assertEquals(22_500, b.balanceMinor(owner));
    }

    @Test void unknownCommitIsReconciledByDurableReceiptAfterRestart() {
        var owner = UUID.randomUUID(); var transaction = UUID.randomUUID();
        var b = new BankStore(dir, Logger.getAnonymousLogger(), () -> { throw new IOException("after rename"); });
        b.load();
        assertEquals(FAILED, b.creditBoostCompensationOnce(owner, transaction, 15_000));
        assertFalse(b.available());
        b = bank();
        assertTrue(b.available());
        assertEquals(ALREADY, b.creditBoostCompensationOnce(owner, transaction, 15_000));
        assertEquals(15_000, b.balanceMinor(owner));
    }

    @Test void failureBeforeReplaceCanBeRetriedAfterRestart() throws Exception {
        var owner = UUID.randomUUID(); var transaction = UUID.randomUUID();
        var b = bank();
        java.nio.file.Files.createDirectory(dir.resolve("bank.json.tmp"));
        assertEquals(FAILED, b.creditBoostCompensationOnce(owner, transaction, 15_000));
        assertFalse(b.available());
        java.nio.file.Files.delete(dir.resolve("bank.json.tmp"));
        b = bank();
        assertEquals(0, b.balanceMinor(owner));
        assertEquals(ADDED, b.creditBoostCompensationOnce(owner, transaction, 15_000));
        assertEquals(ALREADY, b.creditBoostCompensationOnce(owner, transaction, 15_000));
        assertEquals(15_000, b.balanceMinor(owner));
    }

    @Test void invalidAndFullBankNeverCreateReceipt() {
        var owner = UUID.randomUUID(); var transaction = UUID.randomUUID(); var b = bank();
        assertEquals(FAILED, b.creditBoostCompensationOnce(owner, transaction, 0));
        assertEquals(FAILED, b.creditBoostCompensationOnce(owner, transaction, BankStore.MAX_BALANCE * 100L + 1));
        assertEquals(BankStore.MAX_BALANCE, b.deposit(owner, "Steve", BankStore.MAX_BALANCE));
        assertEquals(FULL, b.creditBoostCompensationOnce(owner, transaction, 1));
        assertEquals(BankStore.MAX_BALANCE * 100L, b.balanceMinor(owner));
    }
}

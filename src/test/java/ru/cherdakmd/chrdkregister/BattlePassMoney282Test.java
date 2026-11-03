package ru.cherdakmd.chrdkregister;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.MoneyGrant.*;

class BattlePassMoney282Test {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID();
    final String receipt = "bp:" + "a".repeat(64);
    final UUID lineage = UUID.nameUUIDFromBytes("CHRDK:BattlePass:money:v1".getBytes(StandardCharsets.UTF_8));
    BankStore load() { var bank = new BankStore(dir, Logger.getAnonymousLogger()); bank.load(); return bank; }
    BankStore registered() { var bank=load(); assertTrue(bank.registerIncomeSource("battlepass-rewards", lineage)); return bank; }

    @Test void onceReceiptSurvivesSpendingRestartAndChangesOfRewardAmount() {
        var bank=registered();
        assertEquals(ADDED, bank.grantBattlePassMoneyOnce(owner, 40, receipt));
        assertEquals(40, load().balance(owner));
        assertTrue(bank.withdraw(owner,30)); bank.saveNow();
        bank=load();
        assertEquals(ALREADY, bank.grantBattlePassMoneyOnce(owner,400,receipt));
        assertEquals(ALREADY, bank.grantBattlePassMoneyOnce(owner,1,receipt));
        assertEquals(10, load().balance(owner));
        assertEquals(FAILED, bank.grantBattlePassMoneyOnce(UUID.randomUUID(),40,receipt));
        assertEquals(ADDED, bank.grantBattlePassMoneyOnce(owner,60,"bp:"+"b".repeat(64)));
        assertEquals(70,load().balance(owner));
    }
    @Test void fullWalletNeverTruncatesMoneyOrBurnsReceipt() {
        var bank=registered(); bank.deposit(owner,"Steve",BankStore.MAX_BALANCE-10); bank.saveNow();
        assertEquals(FULL,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        assertEquals(BankStore.MAX_BALANCE-10,load().balance(owner));
        assertTrue(bank.withdraw(owner,30));
        assertEquals(ADDED,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        assertEquals(BankStore.MAX_BALANCE,load().balance(owner));
        assertEquals(ALREADY,bank.grantBattlePassMoneyOnce(owner,40,receipt));
    }
    @Test void unknownDomainBadReceiptAndBadAmountFailClosed() {
        var bank=load();
        assertEquals(FAILED,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        bank=registered();
        for(long amount:new long[]{-1,0,BankStore.MAX_BALANCE+1,Long.MAX_VALUE})
            assertEquals(FAILED,bank.grantBattlePassMoneyOnce(owner,amount,receipt));
        assertEquals(FAILED,bank.grantBattlePassMoneyOnce(owner,40,"../bad"));
        assertEquals(0,load().balance(owner));
    }
    @Test void ioFailureBeforeReplaceDoesNotMintOrAcknowledge() throws Exception {
        var bank=registered();Files.createDirectory(dir.resolve("bank.json.tmp"));
        assertEquals(FAILED,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        assertFalse(bank.available());assertEquals(0,load().balance(owner));
        Files.delete(dir.resolve("bank.json.tmp"));bank=load();
        assertEquals(ADDED,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        assertEquals(40,load().balance(owner));
    }
    @Test void ioFailureAfterReplaceIsReconciledWithoutDuplicateCredit() {
        registered();
        var bank=new BankStore(dir,Logger.getAnonymousLogger(),()->{throw new IOException("after replace");});bank.load();
        assertEquals(FAILED,bank.grantBattlePassMoneyOnce(owner,40,receipt));
        assertFalse(bank.available());
        var restored=load();
        assertEquals(ALREADY,restored.grantBattlePassMoneyOnce(owner,40,receipt));
        assertEquals(40,restored.balance(owner));
        bank.saveNow(); assertEquals(40,load().balance(owner));
    }
    @Test void displayArithmeticCannotOverflowWithUnlimitedSavedTime() {
        assertTrue(RewardBoostService.minutes(Long.MAX_VALUE)>0);
        assertEquals(Long.MAX_VALUE,RewardBoostMath.grant(Long.MAX_VALUE-1,24,1));
        assertEquals(100000L*RewardBoostMath.HOUR,RewardBoostMath.grant(0,100000,1));
    }
}

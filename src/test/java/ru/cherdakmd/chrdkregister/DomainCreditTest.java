package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.PaidActionStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DomainCreditTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    PaidActionStore load() throws Exception { return new PaidActionStore(dir); }
    PaidActionStore registered() throws Exception { var s=load(); s.initializeValues(Map.of("guild-state","before")); s.markRegistered(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }

    @Test void domainDebitAndCumulativeIncomeSurviveTogetherAndBankRetryDoesNotMint() throws Exception {
        var source=registered(); source.creditValue("guild-state","after",owner,12);
        source=load(); assertEquals("after",source.values().get("guild-state")); assertEquals(12,source.refunds().get(owner));
        var bank=bank(); assertTrue(bank.registerIncomeSource("guilds",source.lineage()));
        assertTrue(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12));
        bank=bank(); assertTrue(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12)); assertEquals(12,bank.balance(owner));
        source.creditValue("guild-state","after-second-withdrawal",owner,5);
        assertEquals(17,load().refunds().get(owner)); assertTrue(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,17)); assertEquals(17,bank.balance(owner));
    }
    @Test void fullAndPartialBankCapKeepRemainderAcrossBothRestarts() throws Exception {
        var source=registered(); source.creditValue("guild-state","withdrawn",owner,12);
        var bank=bank(); bank.registerIncomeSource("guilds",source.lineage()); bank.deposit(owner,"Alex",BankStore.MAX_BALANCE); bank.saveNow();
        assertFalse(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12));
        assertEquals(12,load().refunds().get(owner)); assertEquals(0,bank.creditedEntitlement("guilds",source.lineage(),owner.toString(),owner));
        bank.withdraw(owner,3); assertFalse(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12));
        bank=bank(); assertEquals(3,bank.creditedEntitlement("guilds",source.lineage(),owner.toString(),owner)); assertEquals(12,load().refunds().get(owner));
        bank.withdraw(owner,9); assertTrue(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12));
        bank=bank(); assertTrue(bank.creditEntitlement("guilds",source.lineage(),owner.toString(),owner,12)); assertEquals(BankStore.MAX_BALANCE,bank.balance(owner));
    }
    @Test void sourceBeforeReplaceFailureKeepsBothBeforeImageAndNoIncome() throws Exception {
        var source=registered(); Files.createDirectory(dir.resolve("paid-actions.json.tmp"));
        assertThrows(IOException.class,()->source.creditValue("guild-state","after",owner,12)); assertFalse(source.healthy());
        assertEquals("before",load().values().get("guild-state")); assertTrue(load().refunds().isEmpty());
    }
    @Test void sourceAfterReplaceFailureIsQuarantinedAndReloadFindsBothAfterImages() throws Exception {
        registered(); var source=new PaidActionStore(dir,()->{throw new IOException("after replace");});
        assertThrows(IOException.class,()->source.creditValue("guild-state","after",owner,12)); assertFalse(source.healthy());
        assertEquals("after",load().values().get("guild-state")); assertEquals(12,load().refunds().get(owner));
        assertThrows(IllegalStateException.class,()->source.creditValue("guild-state","repeat",owner,12));
    }
    @Test void frozenInvalidAndOverflowCreditsCannotChangeDomain() throws Exception {
        var source=registered();
        assertThrows(IllegalArgumentException.class,()->source.creditValue("guild-state","bad",null,12));
        assertThrows(IllegalArgumentException.class,()->source.creditValue("guild-state","bad",owner,0));
        source.creditValue("guild-state","first",owner,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->source.creditValue("guild-state","overflow",owner,1));
        assertEquals("first",load().values().get("guild-state")); assertEquals(Long.MAX_VALUE,load().refunds().get(owner));
        source.bind(()->true); assertThrows(IllegalStateException.class,()->source.creditValue("guild-state","frozen",owner,1));
    }
    @Test void wholeDomainMayExceedOldHomeValueLimitButRemainsBounded() throws Exception {
        var source=registered(); String value="x".repeat(20_000); source.putValue("guild-state",value); assertEquals(value,load().values().get("guild-state"));
        String tooLarge="x".repeat(8*1024*1024+1); assertThrows(IllegalArgumentException.class,()->source.putValue("guild-state",tooLarge)); assertEquals(value,load().values().get("guild-state"));
    }
}

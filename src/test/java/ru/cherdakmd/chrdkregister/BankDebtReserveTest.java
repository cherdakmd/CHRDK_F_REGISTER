package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BankDebtReserveTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),lineage=UUID.randomUUID();
    BankStore bank(){var b=new BankStore(dir,java.util.logging.Logger.getAnonymousLogger());b.load();b.deposit(owner,"Steve",150);b.deposit(other,"Alex",20);return b;}
    @Test void reservedPartCannotBeWithdrawnTransferredOrSpent() {var b=bank();b.debtReserve(id->id.equals(owner)?100:0);assertEquals(50,b.spendableBalance(owner));assertFalse(b.withdraw(owner,51));assertFalse(b.transfer(owner,"Steve",other,"Alex",51,0).ok());assertFalse(b.exchange(owner,"Steve",51,0,()->fail("inventory changed")));assertFalse(b.exchangeAccounts(Map.of(owner,-51L,other,51L),()->fail("domain changed")));assertTrue(b.withdraw(owner,50));assertEquals(100,b.balance(owner));}
    @Test void futureIncomeFirstBecomesReservedWithoutPreventingDeposits() {var b=bank();b.withdraw(owner,150);b.debtReserve(id->id.equals(owner)?100:0);assertEquals(0,b.spendableBalance(owner));b.deposit(owner,"Steve",70);assertEquals(0,b.spendableBalance(owner));b.deposit(owner,"Steve",50);assertEquals(20,b.spendableBalance(owner));assertFalse(b.withdraw(owner,21));}
    @Test void onlyExplicitGuildDebtSettlementBypassesReserveAndReplaysOnce() {var b=bank();b.debtReserve(id->id.equals(owner)?100:0);assertTrue(b.registerIncomeSource("guilds",lineage));var charge=new BankCharge("guilds",lineage,1,UUID.randomUUID(),owner,100,100,0,0,"a".repeat(64),"b".repeat(64));assertFalse(b.settleCharge(charge));assertTrue(b.settleCharge(charge,true));assertTrue(b.settleCharge(charge,true));assertEquals(50,b.balance(owner));assertEquals(0,b.spendableBalance(owner));b.debtReserve(id->0);assertEquals(50,b.spendableBalance(owner));}
    @Test void nonGuildSourceCannotClaimDebtCollectorPrivilege() {var b=bank();b.registerIncomeSource("lottery",lineage);var charge=new BankCharge("lottery",lineage,1,UUID.randomUUID(),owner,100,100,0,0,"a".repeat(64),"b".repeat(64));assertFalse(b.settleCharge(charge,true));assertEquals(150,b.balance(owner));}
    @Test void unknownProviderClosesOnlySpendingNotIncomingCredit() {var b=bank();b.debtReserve(id->-1);assertEquals(0,b.spendableBalance(owner));assertFalse(b.withdraw(owner,1));assertEquals(160,b.deposit(owner,"Steve",10));b.debtReserve(id->{throw new IllegalStateException("WAL unavailable");});assertEquals(0,b.spendableBalance(owner));}
}

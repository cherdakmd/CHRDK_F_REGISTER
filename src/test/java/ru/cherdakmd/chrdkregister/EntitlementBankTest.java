package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EntitlementBankTest {
    @TempDir Path dir;
    final UUID lineage=UUID.randomUUID(), owner=UUID.randomUUID();
    BankStore load() { var b=new BankStore(dir,Logger.getAnonymousLogger()); b.load(); return b; }
    BankStore source() { var b=load(); assertTrue(b.registerIncomeSource("lottery",lineage)); return b; }
    @Test void onlyDeltaIsCreditedAcrossSpendingRestartAndFutureIncome() {
        var b=source(); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,100));
        assertTrue(b.withdraw(owner,90)); b.saveNow(); b=load();
        assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,100)); assertEquals(10,b.balance(owner));
        assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,130)); assertEquals(40,load().balance(owner));
        assertEquals(130,load().creditedEntitlement("lottery",lineage,"player",owner));
    }
    @Test void capDoesNotAcknowledgeAndCanBeRetriedWithoutLosingClaim() {
        var b=source(); b.deposit(owner,"Player",BankStore.MAX_BALANCE);
        assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,7)); assertEquals(0,b.creditedEntitlement("lottery",lineage,"player",owner));
        assertTrue(b.withdraw(owner,7)); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,7));
        assertEquals(BankStore.MAX_BALANCE,load().balance(owner));
    }
    @Test void resetSourceAndDifferentOwnerOrLowerTotalFailClosed() {
        var b=source(); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,50));
        assertFalse(b.registerIncomeSource("lottery",UUID.randomUUID()));
        assertEquals(-1,b.creditedEntitlement("lottery",UUID.randomUUID(),"player",owner));
        assertFalse(b.creditEntitlement("lottery",lineage,"player",UUID.randomUUID(),50));
        assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,49)); assertEquals(50,b.balance(owner));
    }
    @Test void namespacesDoNotConsumeEachOthersReceipts() {
        var b=source(); UUID goals=UUID.randomUUID(); assertTrue(b.registerIncomeSource("common-goals",goals));
        assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,10));
        assertTrue(b.creditEntitlement("common-goals",goals,"player",owner,15)); assertEquals(25,load().balance(owner));
    }
    @Test void absentRegistrationIsUnknownNotZeroAndCannotMintOnMissingBankFile() {
        var b=load(); assertEquals(-1,b.creditedEntitlement("lottery",lineage,"player",owner));
        assertFalse(b.incomeSourceAvailable("lottery",lineage)); assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,100));
    }
    @Test void totalCanExceedBalanceCapWithoutChangingWholeDiamondRules() {
        var b=source(); long cap=BankStore.MAX_BALANCE;
        assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,cap)); assertTrue(b.withdraw(owner,cap));
        assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,cap+12)); assertEquals(12,b.balance(owner));
        assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,-1));
    }
    @Test void preRenameIoFailureQuarantinesAndDoesNotAcknowledge() throws Exception {
        var b=source(); Files.createDirectory(dir.resolve("bank.json.tmp"));
        assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,40)); assertFalse(b.available());
        assertEquals(-1,b.creditedEntitlement("lottery",lineage,"player",owner));
        assertEquals(0,load().creditedEntitlement("lottery",lineage,"player",owner));
    }
    @Test void postRenameIoFailureIsUnknownUntilReloadThenRetryIsNoop() throws Exception {
        source(); var ambiguous=new BankStore(dir,Logger.getAnonymousLogger(),()->{throw new IOException("after replace");}); ambiguous.load();
        assertFalse(ambiguous.creditEntitlement("lottery",lineage,"player",owner,40));
        assertEquals(-1,ambiguous.creditedEntitlement("lottery",lineage,"player",owner));
        var restored=load(); assertEquals(40,restored.creditedEntitlement("lottery",lineage,"player",owner));
        assertTrue(restored.creditEntitlement("lottery",lineage,"player",owner,40)); assertEquals(40,restored.balance(owner));
        ambiguous.saveNow(); assertEquals(40,load().balance(owner));
    }
    @Test void missingTablesAndReceiptWithoutAccountAreCorruptionNotEmptyIncome() throws Exception {
        var b=source(); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,3));
        String original=Files.readString(b.file());
        for (String field:List.of("entitlementCredits","entitlementSources","accounts")) {
            var object=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); object.remove(field);
            Files.writeString(b.file(),object.toString()); assertFalse(load().available());
        }
        var object=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); object.getAsJsonObject("accounts").remove(owner.toString());
        Files.writeString(b.file(),object.toString()); assertFalse(load().available());
    }
    @Test void ordinarySaveAndAuctionImportPreserveIncomeReceipts() {
        var b=source(); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,3));
        assertTrue(b.importLegacyAuction(UUID.randomUUID(),owner,2)); b.deposit(owner,"Player",1); b.saveNow(); b=load();
        assertEquals(3,b.creditedEntitlement("lottery",lineage,"player",owner)); assertEquals(6,b.balance(owner));
    }
    @Test void largeClaimCreditsOnlyRoomAndReceiptRetainsUnpaidRemainder() {
        var b=source(); long cap=BankStore.MAX_BALANCE;
        assertFalse(b.creditEntitlement("lottery",lineage,"player",owner,cap+12));
        assertEquals(cap,load().balance(owner)); assertEquals(cap,load().creditedEntitlement("lottery",lineage,"player",owner));
        assertTrue(b.withdraw(owner,cap)); assertTrue(b.creditEntitlement("lottery",lineage,"player",owner,cap+12));
        assertEquals(12,load().balance(owner));
    }

}

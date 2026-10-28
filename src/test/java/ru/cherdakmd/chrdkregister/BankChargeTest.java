package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BankChargeTest {
    @TempDir Path dir;
    final UUID lineage=UUID.randomUUID(), owner=UUID.randomUUID();
    final String hash="a".repeat(64);
    BankStore load() { var b=new BankStore(dir,Logger.getAnonymousLogger()); b.load(); return b; }
    BankStore funded(long amount) { var b=load(); assertTrue(b.registerIncomeSource("lottery",lineage)); b.deposit(owner,"Alex",amount); b.saveNow(); return b; }
    BankCharge charge(long seq,long debit,long cash,long change) { return new BankCharge("lottery",lineage,seq,UUID.randomUUID(),owner,debit+cash-change,debit,cash,change,hash,hash); }
    @Test void receiptAndBalanceSurviveRestartAndReplayAfterSpendingIsNoop() {
        var b=funded(100); var c=charge(1,20,0,0); assertEquals(BankCharge.Status.MISSING,b.bankChargeStatus(c));
        assertTrue(b.settleCharge(c)); b=load(); assertEquals(80,b.balance(owner)); assertEquals(BankCharge.Status.COMMITTED,b.bankChargeStatus(c));
        b.withdraw(owner,80); b.saveNow(); assertTrue(b.settleCharge(c)); assertEquals(0,load().balance(owner));
    }
    @Test void wholePhysicalBlockChangeAndBankDebitUseOneReceipt() {
        var b=funded(2); var c=charge(1,2,9,6); assertEquals(5,c.price()); assertTrue(b.settleCharge(c));
        assertEquals(6,load().balance(owner)); assertTrue(load().settleCharge(c)); assertEquals(6,load().balance(owner));
    }
    @Test void debitRefusalAndChangeCapDoNotConsumeSequence() {
        var b=funded(1); var c=charge(1,2,0,0); assertFalse(b.settleCharge(c)); assertEquals(BankCharge.Status.MISSING,load().bankChargeStatus(c));
        b.deposit(owner,"Alex",BankStore.MAX_BALANCE-1); b.saveNow(); var capped=charge(1,0,9,8);
        assertFalse(b.settleCharge(capped)); assertEquals(BankCharge.Status.MISSING,load().bankChargeStatus(capped));
        assertEquals(BankStore.MAX_BALANCE,load().balance(owner));
    }
    @Test void changedPayloadOldSequenceAndSkippedSequenceConflict() {
        var b=funded(100); var first=charge(1,10,0,0); assertTrue(b.settleCharge(first));
        assertEquals(BankCharge.Status.CONFLICT,b.bankChargeStatus(charge(1,10,0,0)));
        assertEquals(BankCharge.Status.CONFLICT,b.bankChargeStatus(charge(3,10,0,0)));
        assertTrue(b.settleCharge(charge(2,5,0,0))); assertEquals(BankCharge.Status.CONFLICT,load().bankChargeStatus(first)); assertEquals(85,load().balance(owner));
    }
    @Test void resetBankAndWrongLineageAreUnknownNotMissing() {
        var b=load(); var c=charge(1,1,0,0); assertEquals(BankCharge.Status.UNAVAILABLE,b.bankChargeStatus(c));
        assertFalse(b.settleCharge(c)); b.registerIncomeSource("lottery",UUID.randomUUID()); assertEquals(BankCharge.Status.UNAVAILABLE,b.bankChargeStatus(c));
    }
    @Test void postRenameFailureQuarantinesThenAuthoritativeReceiptResolves() {
        funded(100); var c=charge(1,10,0,0);
        var b=new BankStore(dir,Logger.getAnonymousLogger(),()->{throw new IOException("after rename");}); b.load();
        assertFalse(b.settleCharge(c)); assertEquals(BankCharge.Status.UNAVAILABLE,b.bankChargeStatus(c)); b.saveNow();
        assertEquals(BankCharge.Status.COMMITTED,load().bankChargeStatus(c)); assertEquals(90,load().balance(owner));
    }
    @Test void preRenameFailureLeavesMissingReceiptAndOriginalFunds() throws Exception {
        var b=funded(100); var c=charge(1,10,0,0); Files.createDirectory(dir.resolve("bank.json.tmp"));
        assertFalse(b.settleCharge(c)); assertFalse(b.available()); assertEquals(BankCharge.Status.MISSING,load().bankChargeStatus(c)); assertEquals(100,load().balance(owner));
    }
    @Test void ordinarySaveIncomeAndAuctionPublicationPreserveCharge() {
        var b=funded(100); var c=charge(1,10,0,0); assertTrue(b.settleCharge(c));
        assertTrue(b.creditEntitlement("lottery",lineage,"prize",owner,5)); assertTrue(b.importLegacyAuction(UUID.randomUUID(),owner,2));
        b.deposit(owner,"Alex",1); b.saveNow(); assertEquals(BankCharge.Status.COMMITTED,load().bankChargeStatus(c)); assertEquals(98,load().balance(owner));
    }
    @Test void schemaThreeMigratesWithoutInventingChargesAndCurrentRequiresTable() throws Exception {
        var b=funded(10); var json=com.google.gson.JsonParser.parseString(Files.readString(b.file())).getAsJsonObject();
        json.addProperty("schema",3); json.remove("chargeSettlements"); json.remove("chargeWitnesses"); json.remove("cashPending"); json.remove("cashReceipts"); Files.writeString(b.file(),json.toString());
        b=load(); assertTrue(b.available()); assertTrue(b.settleCharge(charge(1,1,0,0))); assertEquals(9,load().balance(owner));
        json=com.google.gson.JsonParser.parseString(Files.readString(b.file())).getAsJsonObject(); json.remove("chargeSettlements"); Files.writeString(b.file(),json.toString()); assertFalse(load().available());
    }
    @Test void receiptWithoutAccountOrWithAlteredIdentityIsCorruption() throws Exception {
        var b=funded(10); assertTrue(b.settleCharge(charge(1,1,0,0))); String original=Files.readString(b.file());
        var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("accounts").remove(owner.toString()); Files.writeString(b.file(),json.toString()); assertFalse(load().available());
        json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("chargeSettlements").getAsJsonObject("lottery").addProperty("lineage",UUID.randomUUID().toString());
        Files.writeString(b.file(),json.toString()); assertFalse(load().available());
    }
    @Test void cashWitnessSurvivesReceiptCompactionOtherBuyersAndBankOnlyPayments() {
        var b=funded(2); var physical=charge(1,2,9,6); assertTrue(b.settleCharge(physical));
        assertEquals(Map.of("lottery",physical.transaction()),load().chargeWitnesses(owner));
        var other=UUID.randomUUID(); var second=new BankCharge("lottery",lineage,2,UUID.randomUUID(),other,1,0,9,8,hash,hash); assertTrue(b.settleCharge(second));
        assertTrue(b.settleCharge(charge(3,1,0,0)));
        assertEquals(Map.of("lottery",physical.transaction()),load().chargeWitnesses(owner)); assertEquals(Map.of("lottery",second.transaction()),load().chargeWitnesses(other));
    }
    @Test void missingCashWitnessTableOrWitnessIsCorruptionRatherThanUntouchedPlayerdata() throws Exception {
        var b=funded(2); assertTrue(b.settleCharge(charge(1,2,9,6))); String original=Files.readString(b.file());
        var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.remove("chargeWitnesses"); Files.writeString(b.file(),json.toString()); assertFalse(load().available()); assertNull(load().chargeWitnesses(owner));
        json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("chargeWitnesses").remove("lottery:"+owner); Files.writeString(b.file(),json.toString()); assertFalse(load().available());
    }
    @Test void namespacesHaveIndependentExpenseAndIncomeSequences() {
        var b=funded(100); var lottery=charge(1,10,0,0); assertTrue(b.settleCharge(lottery)); var goals=UUID.randomUUID(); b.registerIncomeSource("common-goals",goals);
        var other=new BankCharge("common-goals",goals,1,UUID.randomUUID(),owner,3,3,0,0,hash,hash); assertTrue(b.settleCharge(other));
        assertTrue(b.creditEntitlement("common-goals",goals,"alex",owner,2)); assertEquals(BankCharge.Status.COMMITTED,load().bankChargeStatus(lottery)); assertEquals(BankCharge.Status.COMMITTED,load().bankChargeStatus(other)); assertEquals(89,load().balance(owner));
    }
}

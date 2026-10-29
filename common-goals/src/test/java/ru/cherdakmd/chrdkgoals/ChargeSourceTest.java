package ru.cherdakmd.chrdkgoals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class ChargeSourceTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    final String inventory=new com.google.gson.Gson().toJson(new String[36]);
    GoalStore load() throws Exception { var s=new GoalStore(dir,Logger.getAnonymousLogger()); s.load(); return s; }
    GoalStore source() throws Exception { var s=load(); s.enableBank(); s.markBankRegistered(); s.replaceGoals(List.of(new GoalState("donate-diamonds",5,10)),1000); s.saveStrict(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    ChrdkRegisterApi api(BankStore b) { return (ChrdkRegisterApi)Proxy.newProxyInstance(ChrdkRegisterApi.class.getClassLoader(),new Class<?>[]{ChrdkRegisterApi.class},(proxy,method,args)-> {
        if (method.getName().equals("bankChargeStatus")) return b.bankChargeStatus((BankCharge)args[0]);
        throw new AssertionError("Bank-only recovery must not access player/inventory: "+method.getName());
    }); }
    ChargeJournal journal(GoalStore s) throws Exception { return new ChargeJournal(dir,"common-goals",s.incomeId()); }
    BankCharge prepare(GoalStore s,ChargeJournal j) throws Exception { String after=s.previewDonation("alex",5,0); return j.prepare(owner,5,5,0,0,inventory,inventory,s.snapshot(),after); }
    @Test void previewIsNotPublishedAndCommittedBankRecoveryWorksOfflineAfterRestart() throws Exception {
        var s=source(); var j=journal(s); var b=bank(); b.registerIncomeSource("common-goals",s.incomeId()); b.deposit(owner,"Alex",20); b.saveNow();
        var c=prepare(s,j); assertFalse(s.goal("donate-diamonds").orElseThrow().done); assertEquals(0,s.entitlementOf("alex")); assertTrue(b.settleCharge(c));
        s=load(); j=journal(s); assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(j,api(bank()),null,s));
        assertTrue(s.goal("donate-diamonds").orElseThrow().done); assertEquals(10,s.entitlementOf("alex")); assertEquals(15,bank().balance(owner)); assertNull(journal(s).pending());
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(j,api(bank()),null,s)); assertTrue(s.goal("donate-diamonds").orElseThrow().done); assertEquals(10,s.entitlementOf("alex"));
    }
    @Test void bankRefusalLeavesSourceUntouchedAndRetryKeepsSequence() throws Exception {
        var s=source(); var j=journal(s); var b=bank(); b.registerIncomeSource("common-goals",s.incomeId()); var c=prepare(s,j);
        assertFalse(b.settleCharge(c)); assertEquals(ChargeRecovery.Decision.ROLLBACK,ChargeInventory.recover(j,api(b),null,s));
        assertFalse(s.goal("donate-diamonds").orElseThrow().done); assertEquals(0,s.entitlementOf("alex")); assertEquals(1,j.nextSequence()); assertNull(j.pending());
    }
    @Test void unavailableBankRetainsObligationAndFreezesOtherMutations() throws Exception {
        var s=source(); var j=journal(s); prepare(s,j); s.bindFinance(()->j.pending()!=null || !j.healthy());
        assertEquals(ChargeRecovery.Decision.WAIT,ChargeInventory.recover(j,api(bank()),null,s)); assertNotNull(j.pending());
        assertThrows(IllegalStateException.class,()->s.addProgress("donate-diamonds","alex",1)); assertThrows(IllegalStateException.class,()->s.setProgress("donate-diamonds",1)); assertThrows(IllegalStateException.class,()->s.replaceGoals(List.of(),2000)); assertFalse(s.needRotation(2000));
    }
    @Test void sourcePublishedButAckLostIsIdempotentAndChangedSourceConflicts() throws Exception {
        var s=source(); var j=journal(s); var b=bank(); b.registerIncomeSource("common-goals",s.incomeId()); b.deposit(owner,"Alex",20); var c=prepare(s,j); assertTrue(b.settleCharge(c));
        var pending=j.pending(); s.publish(pending.beforeState(),pending.afterState()); s=load(); j=journal(s);
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(j,api(bank()),null,s)); assertTrue(s.goal("donate-diamonds").orElseThrow().done); assertEquals(10,s.entitlementOf("alex"));
        var captured=s; assertThrows(IllegalStateException.class,()->captured.publish("neither",pending.beforeState()));
    }
    @Test void lostJournalCannotReplayPastExpenseAndStorageFailureKeepsPaidIntent() throws Exception {
        var s=source(); var j=journal(s); var b=bank(); b.registerIncomeSource("common-goals",s.incomeId()); b.deposit(owner,"Alex",20); var c=prepare(s,j); assertTrue(b.settleCharge(c));
        Files.createDirectory(dir.resolve("goals-data.json.tmp"));
        assertThrows(java.io.IOException.class,()->ChargeInventory.recover(j,api(b),null,s)); assertNotNull(j.pending()); assertFalse(s.healthy());
        Files.delete(dir.resolve("bank-charge.json")); var fresh=journal(s);
        assertEquals(BankCharge.Status.CONFLICT,b.bankChargeStatus(fresh.prepare(owner,5,5,0,0,inventory,inventory,"old","new"))); assertEquals(15,bank().balance(owner));
    }
    @Test void sourcePostRenameFailureKeepsPaidIntentUntilReloadWithoutDuplicatingResult() throws Exception {
        var s=source(); var j=journal(s); var b=bank(); b.registerIncomeSource("common-goals",s.incomeId()); b.deposit(owner,"Alex",20); var c=prepare(s,j); assertTrue(b.settleCharge(c));
        var ambiguous=new GoalStore(dir,Logger.getAnonymousLogger(),()->{throw new java.io.IOException("after source rename");}); ambiguous.load();
        assertThrows(java.io.IOException.class,()->ChargeInventory.recover(j,api(b),null,ambiguous)); assertFalse(ambiguous.healthy()); assertNotNull(j.pending());
        var restored=load(); assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(journal(restored),api(bank()),null,restored));
        assertEquals(15,bank().balance(owner)); assertNull(journal(restored).pending());
        assertTrue(restored.goal("donate-diamonds").orElseThrow().done); assertEquals(10,restored.entitlementOf("alex"));
    }
    @Test void productionUsesDurableBankProtocolAndNeverPhysicalRefunds() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkgoals/GoalsService.java"));
        String payment=code.substring(code.indexOf("public void donate"),code.indexOf("/** Засчитывает прогресс"));
        assertTrue(payment.indexOf("charges.prepare") < payment.indexOf("commitBankCharge")); assertTrue(payment.indexOf("commitBankCharge") < payment.indexOf("ChargeInventory.recover"));
        assertFalse(payment.contains("DiamondWallet")); assertFalse(payment.contains("setStorageContents")); assertFalse(payment.contains("dropItem"));
    }
}

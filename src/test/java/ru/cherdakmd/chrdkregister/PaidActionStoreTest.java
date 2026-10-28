package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class PaidActionStoreTest {
    @TempDir Path dir;
    final UUID payer=UUID.randomUUID(), actor=UUID.randomUUID();
    PaidActionStore load() throws Exception { return new PaidActionStore(dir); }
    PaidActionStore registered() throws Exception { var s=load(); s.markRegistered(); return s; }
    PaidActionStore.Action action() { return new PaidActionStore.Action(UUID.randomUUID(),payer,actor,5,"tpa","world:1:64:2",PaidActionStore.Phase.READY); }
    void publish(PaidActionStore s,PaidActionStore.Action a) throws Exception { var after=s.preview(a); s.publish(s.snapshot(),after); }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    ChrdkRegisterApi api(BankStore b) { return (ChrdkRegisterApi)Proxy.newProxyInstance(ChrdkRegisterApi.class.getClassLoader(),new Class<?>[]{ChrdkRegisterApi.class},(proxy,method,args)->{
        if (method.getName().equals("bankChargeStatus")) return b.bankChargeStatus((BankCharge)args[0]);
        throw new AssertionError("Offline bank-only recovery accessed "+method.getName());
    }); }
    @Test void previewDoesNotPublishAndRestartReadyCanRefundOnlyOnce() throws Exception {
        var s=registered(); var a=action(); String after=s.preview(a); assertTrue(s.actions().isEmpty());
        s.publish(s.snapshot(),after); s=load(); assertTrue(s.occupied(payer)); assertTrue(s.occupied(actor));
        assertTrue(s.refundReady(a.id())); assertFalse(s.refundReady(a.id())); assertEquals(5,load().refunds().get(payer)); assertTrue(load().actions().isEmpty());
    }
    @Test void executingIsNotAutoRefundableOrRepeatableAndSuccessProducesNoRefund() throws Exception {
        var s=registered(); var a=action(); publish(s,a); assertTrue(s.begin(a.id())); s=load();
        assertFalse(s.begin(a.id())); assertFalse(s.refundReady(a.id())); assertTrue(s.finish(a.id(),true)); assertFalse(s.finish(a.id(),false)); assertTrue(load().refunds().isEmpty());
    }
    @Test void falseNativeResultPersistsCumulativeRefundAndDuplicatesCannotMint() throws Exception {
        var s=registered(); var a=action(); publish(s,a); s.begin(a.id()); assertTrue(s.finish(a.id(),false)); assertFalse(s.finish(a.id(),false));
        var next=action(); publish(s,next); s.begin(next.id()); s.finish(next.id(),false); assertEquals(10,load().refunds().get(payer));
    }
    @Test void occupiedPayerOrMoverCannotStartAnotherReservation() throws Exception {
        var s=registered(); publish(s,action()); assertThrows(IllegalStateException.class,()->s.preview(action())); assertTrue(s.occupied(actor));
    }
    @Test void unknownOutcomeRequiresExplicitAuditedResolutionAndCannotDoubleRefund() throws Exception {
        var s=registered(); var a=action(); publish(s,a); s.begin(a.id()); s=load();
        assertTrue(s.resolve(a.id(),false,"Console")); assertFalse(s.resolve(a.id(),false,"Console"));
        assertEquals(5,load().refunds().get(payer)); var audit=load().resolutions().getFirst(); assertEquals(a.id(),audit.action()); assertEquals("Console",audit.operator()); assertFalse(audit.success());
    }
    @Test void sourceBeforeRenameFailureKeepsReservationAndQuarantines() throws Exception {
        var s=registered(); var a=action(); publish(s,a); Files.createDirectory(dir.resolve("paid-actions.json.tmp"));
        assertThrows(IOException.class,()->s.begin(a.id())); assertFalse(s.healthy()); assertEquals(PaidActionStore.Phase.READY,load().actions().getFirst().phase());
    }
    @Test void afterRenameFailureIsAmbiguousUntilReloadAndNeverBlindlyRefundsExecution() throws Exception {
        var s=registered(); var a=action(); publish(s,a);
        var ambiguous=new PaidActionStore(dir,()->{throw new IOException("after source rename");});
        assertThrows(IOException.class,()->ambiguous.begin(a.id())); assertFalse(ambiguous.healthy());
        assertEquals(PaidActionStore.Phase.EXECUTING,load().actions().getFirst().phase()); assertFalse(load().refundReady(a.id()));
    }
    @Test void corruptionMissingTablesAndFractionalMoneyDoNotBecomeEmptySource() throws Exception {
        var s=registered(); var a=action(); publish(s,a); String original=Files.readString(dir.resolve("paid-actions.json"));
        for (String field:List.of("lineage","registered","actions","refunds","valuesInitialized")) {
            var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.remove(field); String bad=json.toString(); Files.writeString(dir.resolve("paid-actions.json"),bad);
            assertThrows(IOException.class,this::load); assertEquals(bad,Files.readString(dir.resolve("paid-actions.json")));
        }
        var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("actions").getAsJsonObject(a.id().toString()).addProperty("price",1.5);
        Files.writeString(dir.resolve("paid-actions.json"),json.toString()); assertThrows(IOException.class,this::load);
    }
    @Test void valuesImportOnlyOnceAndPaidPreviewCannotOverwriteBeforeReceipt() throws Exception {
        var s=registered(); s.initializeValues(Map.of("home","old")); s.initializeValues(Map.of("home","wrong")); assertEquals("old",s.values().get("home"));
        var after=s.previewValue("home","new"); assertEquals("old",load().values().get("home")); s.publish(s.snapshot(),after); assertEquals("new",load().values().get("home"));
    }
    @Test void actualBankJournalProtocolRecoversOfflineThenRefundsReadyWithCapAndRestart() throws Exception {
        var s=registered(); var b=bank(); b.registerIncomeSource("teleports",s.lineage()); b.deposit(payer,"Alex",10); b.saveNow();
        var j=new ChargeJournal(dir,"teleports",s.lineage()); String inv=new com.google.gson.Gson().toJson(new String[36]); var a=action();
        String after=s.preview(a); var c=j.prepare(payer,5,5,0,0,inv,inv,s.snapshot(),after); assertTrue(b.settleCharge(c));
        s=load(); j=new ChargeJournal(dir,"teleports",s.lineage()); assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(j,api(bank()),null,s));
        assertTrue(s.refundReady(a.id())); b=bank(); b.deposit(payer,"Alex",BankStore.MAX_BALANCE-5); b.saveNow();
        assertFalse(b.creditEntitlement("teleports",s.lineage(),payer.toString(),payer,5)); assertEquals(5,load().refunds().get(payer));
        b.withdraw(payer,5); assertTrue(b.creditEntitlement("teleports",s.lineage(),payer.toString(),payer,5));
        b=bank(); assertTrue(b.creditEntitlement("teleports",s.lineage(),payer.toString(),payer,5)); assertEquals(BankStore.MAX_BALANCE,b.balance(payer));
    }
    @Test void missingRegistrationAndRestoredOlderBankDoNotMatchJournalCursor() throws Exception {
        var s=registered(); var b=bank(); assertEquals(-1,b.bankChargeSequence("teleports",s.lineage())); b.registerIncomeSource("teleports",s.lineage()); assertEquals(0,b.bankChargeSequence("teleports",s.lineage()));
        String old=Files.readString(b.file()); b.deposit(payer,"Alex",10); var j=new ChargeJournal(dir,"teleports",s.lineage()); var inv=new com.google.gson.Gson().toJson(new String[36]);
        String after=s.preview(action()); var c=j.prepare(payer,5,5,0,0,inv,inv,s.snapshot(),after); b.settleCharge(c); ChargeInventory.recover(j,api(b),null,s);
        assertEquals(j.nextSequence()-1,bank().bankChargeSequence("teleports",s.lineage())); Files.writeString(b.file(),old);
        assertNotEquals(j.nextSequence()-1,bank().bankChargeSequence("teleports",s.lineage()));
    }
    @Test void paymentJournalBlocksSourceMutationsUntilRecovery() throws Exception {
        var s=registered(); s.bind(()->true); assertThrows(IllegalStateException.class,()->s.putValue("home","overwrite")); assertThrows(IllegalStateException.class,()->s.preview(action()));
    }
}

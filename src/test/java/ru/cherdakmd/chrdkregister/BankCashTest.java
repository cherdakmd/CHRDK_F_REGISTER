package ru.cherdakmd.chrdkregister;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный canonical BankStore + production executor. Fake только для native playerdata. */
class BankCashTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),lineage=UUID.randomUUID();
    final Logger log=Logger.getAnonymousLogger();
    BankStore load() { var b=new BankStore(dir,log); b.load(); return b; }
    BankStore funded(long n) { var b=load(); b.open(owner,"Steve"); if(n>0) b.deposit(owner,"Steve",n); b.flush(); return b; }
    BankCash.Intent intent(BankStore b,BankCash.Kind kind,long amount) { return b.prepareCash(owner,"Steve",kind,amount,9,-1,kind==BankCash.Kind.DEPOSIT?-1:amount,"before","after"); }
    BankCash.Intent pending(BankCash.Kind kind) { var b=funded(100); var p=intent(b,kind,9); assertNotNull(p); return p; }
    InventoryTransferTest.Bag bag() { return new InventoryTransferTest.Bag(owner); }
    @Test void depositIntentAndOwnerReservationAreDurableButNotYetSpendableIncome() {
        var p=pending(BankCash.Kind.DEPOSIT); var b=load(); assertEquals(p,b.cashPending(owner)); assertEquals(100,b.balance(owner)); assertTrue(b.cashLocked(owner)); assertNull(b.cashReceipt(owner));
    }
    @Test void withdrawalIntentDoesNotGiveItemsOrDebitBeforeNativeProof() {
        pending(BankCash.Kind.WITHDRAW); var b=load(); assertEquals(100,b.balance(owner)); assertTrue(b.cashLocked(owner)); assertFalse(b.withdraw(owner,1));
    }
    @Test void depositPublishesBalanceReceiptAndRemovesIntentInOneReplace() throws Exception {
        var p=pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag));
        var b=load(); assertEquals(109,b.balance(owner)); assertNull(b.cashPending(owner)); assertTrue(b.cashReceipt(owner).matches(p)); assertEquals(p.transaction().toString(),nativeBag.durableToken); assertEquals(1,nativeBag.applies);
    }
    @Test void withdrawalPublishesDebitOnlyAfterDurableNativeGrant() throws Exception {
        var p=pending(BankCash.Kind.WITHDRAW); var nativeBag=bag(); assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag));
        assertEquals(91,load().balance(owner)); assertTrue(load().cashReceipt(owner).matches(p)); assertEquals("after",nativeBag.durableState); assertEquals(1,nativeBag.applies);
    }
    @Test void spendingAndRestartCannotReplayCompletedDeposit() throws Exception {
        var p=pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); BankCash.recover(load(),owner,nativeBag); var b=load(); assertTrue(b.withdraw(owner,109)); b.flush();
        assertTrue(load().finishCash(p)); assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag)); assertEquals(0,load().balance(owner)); assertEquals(1,nativeBag.applies);
    }
    @Test void retryAfterCompletedWithdrawalDoesNotGrantAgainOrOverwriteCurrentInventory() throws Exception {
        pending(BankCash.Kind.WITHDRAW); var nativeBag=bag(); BankCash.recover(load(),owner,nativeBag); nativeBag.state="later inventory";
        assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag)); assertEquals(91,load().balance(owner)); assertEquals(1,nativeBag.applies); assertEquals("later inventory",nativeBag.state);
    }
    @Test void nextExchangeHasIndependentNonceAndMonotonicReceipt() throws Exception {
        var first=pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); BankCash.recover(load(),owner,nativeBag); nativeBag.state="before";
        var b=load(); var next=intent(b,BankCash.Kind.WITHDRAW,9); assertEquals(2,next.sequence()); assertNotEquals(first.transaction(),next.transaction());
        BankCash.recover(b,owner,nativeBag); assertEquals(100,load().balance(owner)); assertEquals(2,load().cashReceipt(owner).sequence()); assertEquals(2,nativeBag.applies); assertFalse(load().finishCash(first));
    }
    @Test void matchingAfterWithoutNonceCannotAuthorizeDepositOrWithdrawal() throws Exception {
        for(var kind:new BankCash.Kind[]{BankCash.Kind.DEPOSIT,BankCash.Kind.WITHDRAW}) {
            Path separate=dir.resolve(kind.name()); var b=new BankStore(separate,log); b.deposit(owner,"Steve",100); b.flush(); intent(b,kind,9); var nativeBag=bag(); nativeBag.state="after";
            assertEquals(InventoryTransfer.Decision.CONFLICT,BankCash.recover(b,owner,nativeBag)); assertEquals(100,b.balance(owner)); assertNotNull(b.cashPending(owner)); assertEquals(0,nativeBag.applies);
        }
    }
    @Test void nativeSaveExceptionLeavesBankAndPendingUnchangedUntilSuccessfulCheckpoint() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); nativeBag.failSave=true;
        assertThrows(IOException.class,()->BankCash.recover(load(),owner,nativeBag)); assertThrows(IOException.class,()->BankCash.recover(load(),owner,nativeBag)); assertEquals(100,load().balance(owner)); assertNotNull(load().cashPending(owner));
        nativeBag.failSave=false; assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag)); assertEquals(109,load().balance(owner)); assertEquals(1,nativeBag.applies);
    }
    @Test void beforeRenamePrepareFailureCannotCauseAnyNativeMutation() throws Exception {
        funded(100); Files.createDirectory(dir.resolve("bank.json.tmp")); var b=load(); assertNull(intent(b,BankCash.Kind.DEPOSIT,9)); assertFalse(b.available()); b.flush(); assertNull(load().cashPending(owner)); assertEquals(100,load().balance(owner));
    }
    @Test void afterRenamePrepareFailureRecoversThePersistedReservation() throws Exception {
        funded(100); var failed=new BankStore(dir,log,()->{throw new IOException("after prepare rename");}); failed.load(); assertNull(intent(failed,BankCash.Kind.DEPOSIT,9)); assertFalse(failed.available()); failed.flush(); assertNotNull(load().cashPending(owner));
        var nativeBag=bag(); assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag)); assertEquals(109,load().balance(owner));
    }
    @Test void beforeRenameCompletionFailureRetainsIntentAndNeverRepeatsNativeGrant() throws Exception {
        pending(BankCash.Kind.WITHDRAW); Files.createDirectory(dir.resolve("bank.json.tmp")); var failed=load(); var nativeBag=bag();
        assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(failed,owner,nativeBag)); assertFalse(failed.available()); assertEquals(100,load().balance(owner)); assertNotNull(load().cashPending(owner));
        Files.delete(dir.resolve("bank.json.tmp")); assertEquals(InventoryTransfer.Decision.APPLIED,BankCash.recover(load(),owner,nativeBag)); assertEquals(91,load().balance(owner)); assertEquals(1,nativeBag.applies);
    }
    @Test void afterRenameCompletionFailureKeepsReceiptAndBalanceTogether() throws Exception {
        var p=pending(BankCash.Kind.WITHDRAW); var failed=new BankStore(dir,log,()->{throw new IOException("after completion rename");}); failed.load(); var nativeBag=bag();
        assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(failed,owner,nativeBag)); assertFalse(failed.available()); failed.flush(); assertNull(load().cashPending(owner)); assertTrue(load().cashReceipt(owner).matches(p)); assertEquals(91,load().balance(owner));
        BankCash.recover(load(),owner,nativeBag); assertEquals(1,nativeBag.applies);
    }
    @Test void runtimeCompletionAmbiguityAlsoQuarantines() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var failed=new BankStore(dir,log,()->{throw new IllegalStateException("runtime after rename");}); failed.load();
        assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(failed,owner,bag())); assertFalse(failed.available()); failed.flush(); assertEquals(109,load().balance(owner)); assertNull(load().cashPending(owner));
    }
    @Test void offlineWrongUuidAndUnavailableBankWaitWithoutApplying() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); nativeBag.online=false; assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(load(),owner,nativeBag)); nativeBag.online=true; nativeBag.owner=other;
        assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(load(),owner,nativeBag)); assertEquals(InventoryTransfer.Decision.WAIT,BankCash.recover(load(),owner,null)); assertEquals(0,nativeBag.applies);
    }
    @Test void priorReceiptMismatchAndOrphanNonceDoNotPermitNewExchange() throws Exception {
        var b=funded(100); var nativeBag=bag(); nativeBag.token=UUID.randomUUID().toString(); assertEquals(InventoryTransfer.Decision.CONFLICT,BankCash.recover(b,owner,nativeBag));
        intent(b,BankCash.Kind.DEPOSIT,9); assertEquals(InventoryTransfer.Decision.CONFLICT,BankCash.recover(b,owner,nativeBag)); assertEquals(0,nativeBag.applies);
    }
    @Test void rolledBackPlayerdataAfterCompletionFailsClosed() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var nativeBag=bag(); BankCash.recover(load(),owner,nativeBag); nativeBag.token=null; nativeBag.state="before";
        assertEquals(InventoryTransfer.Decision.CONFLICT,BankCash.recover(load(),owner,nativeBag)); assertEquals(109,load().balance(owner)); assertEquals(1,nativeBag.applies);
    }
    @Test void depositCapAndInsufficientWithdrawalRefuseBeforeIntent() {
        var b=funded(BankStore.MAX_BALANCE); assertNull(intent(b,BankCash.Kind.DEPOSIT,1)); assertFalse(b.cashLocked(owner));
        assertNull(intent(b,BankCash.Kind.WITHDRAW,2305)); assertNull(b.prepareCash(owner,"Steve",BankCash.Kind.WITHDRAW,BankStore.MAX_BALANCE+1,1,-1,BankStore.MAX_BALANCE+1,"b","a"));
    }
    @Test void ownerReservationBlocksAllLegacyMoneyMutationCallbacksButNotOtherAccounts() {
        pending(BankCash.Kind.DEPOSIT); var b=load(); assertEquals(-1,b.deposit(owner,"Steve",1)); assertFalse(b.withdraw(owner,1)); assertEquals(100,b.setBalance(owner,"Steve",0));
        assertFalse(b.exchange(owner,"Steve",1,0,()->fail("callback must not run"))); assertFalse(b.exchangeAccounts(Map.of(owner,-1L),()->fail("callback must not run")));
        assertFalse(b.importLegacyShop(owner,1)); assertFalse(b.importLegacyClaims(owner,"Steve",1)); assertFalse(b.importLegacyAuction(lineage,owner,1));
        assertEquals(BankStore.Status.UNAVAILABLE,b.transfer(owner,"Steve",other,"Alex",1,0).status()); assertEquals(BankStore.Status.UNAVAILABLE,b.transfer(other,"Alex",owner,"Steve",1,0).status());
        assertEquals(10,b.deposit(other,"Alex",10)); assertTrue(b.withdraw(other,1)); b.flush(); assertEquals(9,load().balance(other)); assertEquals(100,load().balance(owner)); assertTrue(load().cashLocked(owner));
    }
    @Test void pendingOwnerIncomeAndChargeQueueWithoutLosingTheirOwnSources() {
        pending(BankCash.Kind.DEPOSIT); var b=load(); b.registerIncomeSource("lottery",lineage);
        assertFalse(b.creditEntitlement("lottery",lineage,owner.toString(),owner,5)); assertEquals(0,b.creditedEntitlement("lottery",lineage,owner.toString(),owner));
        var c=new BankCharge("lottery",lineage,1,UUID.randomUUID(),owner,1,1,0,0,"a".repeat(64),"b".repeat(64)); assertFalse(b.settleCharge(c)); assertEquals(BankCharge.Status.MISSING,b.bankChargeStatus(c));
    }
    @Test void auctionCannotChangeEitherReservedPartyBalance() {
        var b=funded(100); b.importLegacyAuction(lineage,owner,0); b.importLegacyAuction(lineage,other,0); b.deposit(other,"Other",100); b.flush(); intent(b,BankCash.Kind.DEPOSIT,9);
        var p=new AuctionPayment(UUID.randomUUID(),lineage,1,1,other,owner,10,0,10,0,0,"a".repeat(64)); assertFalse(b.settleAuction(p)); assertEquals(AuctionPayment.Status.MISSING,b.auctionPaymentStatus(p));
    }
    @Test void legacyV4MigratesWithoutInventingMoneyOrRetroactivelyExchangingItems() throws Exception {
        funded(100); var json=JsonParser.parseString(Files.readString(load().file())).getAsJsonObject(); json.addProperty("schema",4); json.remove("cashPending"); json.remove("cashReceipts"); json.remove("shopSettlements"); json.remove("claimSettlements"); Files.writeString(load().file(),json.toString());
        var b=load(); assertTrue(b.available()); assertEquals(100,b.balance(owner)); assertNull(b.cashReceipt(owner)); assertFalse(b.cashLocked(owner)); assertNotNull(intent(b,BankCash.Kind.DEPOSIT,9)); assertEquals(BankStore.SCHEMA_VERSION,JsonParser.parseString(Files.readString(b.file())).getAsJsonObject().get("schema").getAsInt());
    }
    void refuse(String original) throws Exception {
        Files.writeString(dir.resolve("bank.json"),original); var b=load(); assertFalse(b.available()); b.flush(); assertEquals(original,Files.readString(b.file()));
    }
    @Test void strictMetadataRejectsMissingTablesDuplicateJsonAndTrailingGarbage() throws Exception {
        pending(BankCash.Kind.DEPOSIT); String original=Files.readString(load().file()); var root=JsonParser.parseString(original).getAsJsonObject();
        for(String field:List.of("cashPending","cashReceipts")) { var bad=root.deepCopy(); bad.remove(field); refuse(bad.toString()); }
        refuse(original+" trailing"); refuse(original.replace("\"schema\": "+BankStore.SCHEMA_VERSION,"\"schema\": "+BankStore.SCHEMA_VERSION+", \"schema\": 4"));
    }
    @Test void malformedAmountsUuidSequenceBalanceAndIntOverflowAreNotCoerced() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var root=JsonParser.parseString(Files.readString(load().file())).getAsJsonObject();
        for(var field:List.of("amount","beforeBalance","sequence","selected","blockWorth")) for(String value:List.of("\"1\"","1.5","9223372036854775808")) {
            var bad=root.deepCopy(); bad.getAsJsonObject("cashPending").getAsJsonObject(owner.toString()).add(field,JsonParser.parseString(value)); refuse(bad.toString());
        }
        var bad=root.deepCopy(); bad.getAsJsonObject("cashPending").getAsJsonObject(owner.toString()).addProperty("owner","1-1-1-1-1"); refuse(bad.toString());
        bad=root.deepCopy(); bad.getAsJsonObject("cashPending").getAsJsonObject(owner.toString()).addProperty("beforeBalance",99); refuse(bad.toString());
        bad=root.deepCopy(); bad.getAsJsonObject("cashPending").getAsJsonObject(owner.toString()).addProperty("selected",4294967295L); refuse(bad.toString());
    }
    @Test void schemaDowngradeCannotDiscardPendingReservation() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var root=JsonParser.parseString(Files.readString(load().file())).getAsJsonObject(); root.addProperty("schema",4); refuse(root.toString());
    }
    @Test void snapshotAndAutosaveRetainIntentAndDoNotLoseReceiptOnRestart() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var b=load(); b.deposit(other,"Other",2); b.snapshotJson(); assertTrue(b.isDirty()); b.saveNow(); assertTrue(load().cashLocked(owner));
        BankCash.recover(b,owner,bag()); b.flush(); assertFalse(load().cashLocked(owner)); assertNotNull(load().cashReceipt(owner)); assertEquals(109,load().balance(owner));
    }
    @Test void withdrawalPlanIsExactWholeStorageOnlyAndRejectsInsufficientCapacity() {
        int[] counts=new int[36]; Arrays.fill(counts,-1); counts[0]=63; counts[5]=0; var original=counts.clone();
        var after=BankCashInventory.withdrawal(counts,65); assertEquals(64,after[0]); assertEquals(64,after[5]); assertArrayEquals(original,counts); assertNull(BankCashInventory.withdrawal(counts,66)); assertNull(BankCashInventory.withdrawal(counts,0)); assertNull(BankCashInventory.withdrawal(new int[37],1));
    }
    @Test void nativeWiringHasNoMoneyDropNoOldCallbackAndChecksPlanBeforeRecovery() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/BankService.java")); assertFalse(code.contains("dropItem")); assertFalse(code.contains("addItem(")); assertFalse(code.contains("store.exchange("));
        assertTrue(code.contains("new BankChargeGuard(plugin,store).check(player)")); assertTrue(code.contains("store.prepareCash(")); assertTrue(code.indexOf("!BankCashInventory.valid(pending)")<code.indexOf("BankCash.recover(store")); assertTrue(code.contains("cashAuthenticated(")); assertTrue(code.contains("runTaskTimer(plugin")); assertTrue(code.contains("Math.min(16,players.size())")); assertTrue(code.contains("return !pending&&result=="));
        String plan=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/BankCashInventory.java")); assertTrue(plan.contains("!stack.hasItemMeta()")); assertFalse(plan.contains("getItemInOffHand")); assertFalse(plan.contains("getContents()"));
        String command=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/BankCommandHandler.java")); int recovery=command.indexOf("args[0].equalsIgnoreCase(\"recovery\")"); assertTrue(recovery<command.indexOf("if (!bank.enabled())")); assertTrue(command.indexOf("!sender.hasPermission(\"chrdkregister.admin\")",recovery)<command.indexOf("economy.cash-status",recovery));
    }
    @Test void twoOwnersCanReserveIndependentlyAndFinishingOnePreservesOtherIntent() throws Exception {
        var b=funded(100); b.deposit(other,"Alex",100); b.flush(); var first=intent(b,BankCash.Kind.DEPOSIT,9);
        var second=b.prepareCash(other,"Alex",BankCash.Kind.WITHDRAW,5,1,-1,5,"before","after"); assertNotNull(second);
        BankCash.recover(b,owner,bag()); assertNull(load().cashPending(owner)); assertEquals(second,load().cashPending(other)); assertEquals(100,load().balance(other)); assertEquals(109,load().balance(owner));
    }
    @Test void staleOrMalformedCashReceiptCannotAuthorizeOldPendingSequence() throws Exception {
        pending(BankCash.Kind.DEPOSIT); var b=load(); BankCash.recover(b,owner,bag()); assertNotNull(intent(b,BankCash.Kind.DEPOSIT,9));
        var root=JsonParser.parseString(Files.readString(b.file())).getAsJsonObject(); var bad=root.deepCopy(); bad.getAsJsonObject("cashPending").getAsJsonObject(owner.toString()).addProperty("sequence",1); refuse(bad.toString());
        bad=root.deepCopy(); bad.getAsJsonObject("cashReceipts").getAsJsonObject(owner.toString()).remove("transaction"); refuse(bad.toString());
    }
    @Test void oldAsyncSaveAndNewCashIntentUseTheSameMonitor() throws Exception {
        funded(100); var ref=new java.util.concurrent.atomic.AtomicReference<BankStore>(); var held=new java.util.concurrent.atomic.AtomicBoolean();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1); var started=new java.util.concurrent.CountDownLatch(1);
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>(); var result=new java.util.concurrent.atomic.AtomicReference<BankCash.Intent>();
        var b=new BankStore(dir,log,()->{if(Thread.currentThread().getName().equals("old-bank-save")) {
            held.set(Thread.holdsLock(ref.get())); entered.countDown();
            try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }}); ref.set(b); b.load(); b.deposit(other,"Alex",1);
        var writer=new Thread(b::saveNow,"old-bank-save"); var transaction=new Thread(()->{started.countDown();try {result.set(intent(b,BankCash.Kind.DEPOSIT,9));}catch(Throwable e){error.set(e);}}); writer.start();
        try {assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(held.get()); transaction.start(); assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));} finally {release.countDown();}
        writer.join(5000); transaction.join(5000); assertFalse(writer.isAlive()); assertFalse(transaction.isAlive()); assertNull(error.get()); assertNotNull(result.get()); assertNotNull(load().cashPending(owner)); assertEquals(100,load().balance(owner)); assertEquals(1,load().balance(other));
    }

}

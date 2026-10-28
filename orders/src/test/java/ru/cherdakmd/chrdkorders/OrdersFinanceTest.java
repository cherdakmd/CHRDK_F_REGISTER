package ru.cherdakmd.chrdkorders;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Production source + recovery executor + настоящий BankStore; не подменяем денежную арифметику fake wallet. */
class OrdersFinanceTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    final Logger log=Logger.getAnonymousLogger();
    final long now=1_700_000_000_000L;
    BotOrder order(String id) { return new BotOrder(id,"grom","Гром","ores","IRON_INGOT",10,17,2,0,3,now-1000,now+60000); }
    OrdersStore load() { var s=new OrdersStore(dir,log); s.load(); return s; }
    OrdersStore ready() { var s=load(); s.add(order("1")); s.flush(); return s; }
    OrdersStore.Pending prepare(OrdersStore s) throws Exception { return s.prepare(owner,"Steve",s.order("1"),now,10,0,"before","after"); }
    OrdersStore earned() throws Exception { var s=ready(); var pending=prepare(s); s.publish(pending); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),log); b.load(); return b; }
    static final class Gateway implements MilestoneAwards.Bank {
        final BankStore bank; boolean online=true,known=true;
        Gateway(BankStore bank) { this.bank=bank; }
        public boolean available() { return online&&bank.available(); }
        public boolean registered(String s,UUID l) { return bank.incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return bank.registerIncomeSource(s,l); }
        public boolean known(UUID owner) { return known; }
        public long credited(String s,UUID l,UUID id) { return bank.creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return bank.creditEntitlement(s,l,id.toString(),id,total); }
    }
    @Test void publishRemovesBoardUpdatesLimitsAndCreatesIncomeInSameFile() throws Exception {
        var s=ready(); var p=prepare(s);
        assertNotNull(load().order("1")); assertEquals(0,load().completed(owner)); assertTrue(load().incomes().isEmpty());
        s.publish(p); s=load(); assertNull(s.pending()); assertNull(s.order("1")); assertEquals(1,s.completed(owner)); assertEquals(3,s.reputation(owner)); assertEquals(1,s.today(owner,now)); assertEquals(now,s.lastDelivery(owner)); assertEquals(17,s.incomes().get(owner));
    }
    @Test void repeatPublishAndStaleOrderCannotCreateSecondIncome() throws Exception {
        var s=ready(); var p=prepare(s); s.publish(p);
        assertThrows(IllegalArgumentException.class,()->s.publish(p));
        assertThrows(IllegalStateException.class,()->s.prepare(owner,"Steve",order("1"),now,10,0,"before","after")); assertEquals(17,load().incomes().get(owner));
    }
    @Test void paidIncomeAfterRestartAndSpendingNeverPaysSameDeliveryAgain() throws Exception {
        var s=earned(); var b=bank(); assertTrue(OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(17,b.balance(owner));
        assertTrue(b.withdraw(owner,10)); b.flush(); s=load(); assertTrue(OrdersFinance.deliver(s,new Gateway(bank()),owner,17)); assertEquals(7,bank().balance(owner));
    }
    @Test void nextDynamicOrderAddsOnlyNewDeltaEvenWithSameBotAndMaterial() throws Exception {
        var s=earned(); var b=bank(); OrdersFinance.deliver(s,new Gateway(b),owner,17);
        s.add(order("2")); var p=s.prepare(owner,"Steve",s.order("2"),now+2000,10,0,"second-before","second-after"); s.publish(p);
        assertEquals(34,load().incomes().get(owner)); assertTrue(OrdersFinance.deliver(s,new Gateway(b),owner,34)); assertEquals(34,b.balance(owner)); assertEquals(2,load().completed(owner));
    }
    @Test void fullAndPartialCapRetainSourceObligation() throws Exception {
        var s=earned(); var b=bank(); b.deposit(owner,"Steve",BankStore.MAX_BALANCE); b.flush();
        assertFalse(OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(17,load().incomes().get(owner));
        b.withdraw(owner,5); assertFalse(OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(BankStore.MAX_BALANCE,b.balance(owner));
        b.withdraw(owner,20); assertTrue(OrdersFinance.deliver(load(),new Gateway(b),owner,17)); assertEquals(BankStore.MAX_BALANCE-8,b.balance(owner));
    }
    @Test void bankDownAndUnknownOwnerDoNotLoseDebtOrCreateAccounts() throws Exception {
        var s=earned(); var b=bank(); var gate=new Gateway(b); gate.online=false;
        assertFalse(OrdersFinance.deliver(s,gate,owner,17)); assertFalse(b.knows(owner)); gate.online=true; gate.known=false;
        assertFalse(OrdersFinance.deliver(s,gate,owner,17)); assertFalse(b.knows(owner)); assertEquals(17,load().incomes().get(owner));
        gate.known=true; assertTrue(OrdersFinance.deliver(load(),gate,owner,17)); assertEquals(17,b.balance(owner));
    }
    @Test void lineageConflictDoesNotRegisterReplacementOrPay() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(OrdersStore.SOURCE,UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(0,b.balance(owner));
    }
    @Test void olderSourceTotalCannotBeUsedAsZeroOrReissued() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(OrdersStore.SOURCE,s.lineage()); s.markRegistered(); b.creditEntitlement(OrdersStore.SOURCE,s.lineage(),owner.toString(),owner,18);
        assertThrows(IllegalStateException.class,()->OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(18,b.balance(owner));
    }
    @Test void pendingLocksBoardAdminMutationsAndSecondPlayer() throws Exception {
        var s=ready(); prepare(s);
        assertThrows(IllegalStateException.class,()->s.remove("1")); assertThrows(IllegalStateException.class,s::clear);
        assertThrows(IllegalStateException.class,()->s.purgeExpired(now+999999)); assertThrows(IllegalStateException.class,s::reserveId);
        assertThrows(IllegalStateException.class,()->s.setReputation(owner,"Steve",5)); assertThrows(IllegalStateException.class,()->s.add(order("2")));
        assertThrows(IllegalStateException.class,()->s.prepare(UUID.randomUUID(),"Other",order("1"),now,10,0,"before","after")); assertTrue(load().incomes().isEmpty());
    }
    @Test void dailyCooldownReputationAndStaleSnapshotAreCheckedBeforeIntent() throws Exception {
        var s=ready(); s.recordDelivery(owner,"Steve",now-1000,0);
        assertThrows(IllegalStateException.class,()->s.prepare(owner,"Steve",order("1"),now,1,0,"before","after"));
        assertThrows(IllegalStateException.class,()->s.prepare(owner,"Steve",order("1"),now,10,10,"before","after"));
        var forged=new BotOrder("1","grom","Гром","ores","IRON_INGOT",1,576,0,0,0,now-1000,now+60000);
        assertThrows(IllegalStateException.class,()->s.prepare(owner,"Steve",forged,now,10,0,"before","after")); assertNull(s.pending()); assertTrue(s.incomes().isEmpty());
    }
    @Test void pendingKeepsFrozenRewardAndOriginalUtcDayAcrossRestart() throws Exception {
        var s=ready(); var p=prepare(s); s=load(); assertEquals(p,s.pending()); s.publish(s.pending());
        assertEquals(17,load().incomes().get(owner)); assertEquals(1,s.today(owner,now)); assertEquals(0,s.today(owner,now+OrdersStore.DAY_MILLIS));
    }
    @Test void intentWriteFailureBeforeRenameNeverCreatesIncome() throws Exception {
        ready(); var s=new OrdersStore(dir,log); s.load(); Files.createDirectory(dir.resolve("orders.json.tmp"));
        assertThrows(IOException.class,()->prepare(s)); assertFalse(s.healthy()); s.flush();
        assertNull(load().pending()); assertNotNull(load().order("1")); assertTrue(load().incomes().isEmpty());
    }
    @Test void intentAfterRenameFailureQuarantinesThenReopensDurableIntent() throws Exception {
        ready(); var s=new OrdersStore(dir,log,()->{throw new IOException("after replace");}); s.load();
        assertThrows(IOException.class,()->prepare(s)); assertFalse(s.healthy()); assertNotNull(load().pending()); assertTrue(load().incomes().isEmpty());
    }
    @Test void publishBeforeRenameFailureKeepsIntentRatherThanLosingEarnedMoney() throws Exception {
        var s=ready(); var p=prepare(s); Files.createDirectory(dir.resolve("orders.json.tmp"));
        assertThrows(IOException.class,()->s.publish(p)); assertFalse(s.healthy()); Files.delete(dir.resolve("orders.json.tmp"));
        var reopened=load(); assertNotNull(reopened.pending()); reopened.publish(reopened.pending()); assertEquals(17,load().incomes().get(owner)); assertNull(load().order("1"));
    }
    @Test void publishAfterRenameFailureReloadsCountersAndIncomeTogether() throws Exception {
        var s=ready(); prepare(s); var failing=new OrdersStore(dir,log,()->{throw new IOException("after publish replace");}); failing.load();
        assertThrows(IOException.class,()->failing.publish(failing.pending())); assertFalse(failing.healthy());
        s=load(); assertNull(s.pending()); assertNull(s.order("1")); assertEquals(17,s.incomes().get(owner)); assertEquals(1,s.completed(owner));
    }
    @Test void v1ImportPreservesBoardAndHistoricalStatsWithoutNewMoney() throws Exception {
        var s=ready(); s.recordDelivery(owner,"Steve",now,7); s.flush();
        var json=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); json.addProperty("version",1); json.remove("finance"); Files.writeString(s.file(),json.toString());
        s=load(); UUID lineage=s.lineage(); assertEquals(1,s.completed(owner)); assertEquals(7,s.reputation(owner)); assertNotNull(s.order("1")); assertTrue(s.incomes().isEmpty()); assertEquals(lineage,load().lineage());
    }
    @Test void invalidFinancialMetadataStopsLoadAndDisableCannotOverwrite() throws Exception {
        var s=earned(); String good=Files.readString(s.file());
        for(String bad:List.of(good.replace("\"orders-income\"","\"another-module\""),good.replace("\"registered\": false","\"registered\": \"false\""),good.replace("\"incomes\": {","\"incomes\": ["),"{broken")) {
            Files.writeString(s.file(),bad); var rejected=new OrdersStore(dir,log); assertThrows(IllegalStateException.class,rejected::load); rejected.flush(); assertEquals(bad,Files.readString(s.file()));
        }
    }
    @Test void malformedLegacyRowCannotBeSkippedThenRewrittenAsCleanFinancialSource() throws Exception {
        var s=ready(); var json=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); json.addProperty("version",1); json.remove("finance");
        json.getAsJsonArray("orders").get(0).getAsJsonObject().addProperty("reward","17"); String bad=json.toString(); Files.writeString(s.file(),bad);
        var rejected=new OrdersStore(dir,log); assertThrows(IllegalStateException.class,rejected::load); rejected.flush(); assertEquals(bad,Files.readString(s.file()));
    }
    @Test void asyncFlushCannotPublishSnapshotFromBeforeFinancialCommit() throws Exception {
        ready();
        var ref=new java.util.concurrent.atomic.AtomicReference<OrdersStore>();
        var held=new java.util.concurrent.atomic.AtomicBoolean(); var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var s=new OrdersStore(dir,log,()->{
            if(Thread.currentThread().getName().equals("old-async-save")) {
                held.set(Thread.holdsLock(ref.get())); entered.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        }); ref.set(s); s.load(); s.add(order("2"));
        var started=new java.util.concurrent.CountDownLatch(1);
        var transaction=new Thread(()->{started.countDown(); try { var p=prepare(s); s.publish(p); } catch(Throwable e) { error.set(e); }});
        var writer=new Thread(s::saveNow,"old-async-save"); writer.start();
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(held.get(),"snapshot+replace must hold source monitor");
            transaction.start(); assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); }
        writer.join(5000); transaction.join(5000);
        assertFalse(writer.isAlive()); assertFalse(transaction.isAlive()); assertNull(error.get());
        assertNull(load().pending()); assertNull(load().order("1")); assertNotNull(load().order("2")); assertEquals(17,load().incomes().get(owner));
    }
    @Test void nativeWiringIsStorageOnlyAndHasNoDiamondPayoutFallback() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkorders/OrdersFinance.java"));
        assertTrue(code.contains("getStorageContents()")); assertTrue(code.contains("setStorageContents(")); assertTrue(code.contains("p.saveData()")); assertTrue(code.contains("PersistentDataType.STRING"));
        String service=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkorders/OrdersService.java")); assertFalse(service.contains("Material.DIAMOND")); assertFalse(service.contains("dropItemNaturally")); assertFalse(service.contains("getContents()"));
    }
    static final class NativeBag implements InventoryIncome.Inventory {
        final UUID owner; String state="before",witness; int writes; boolean crash;
        NativeBag(UUID owner) { this.owner=owner; }
        public UUID owner() { return owner; }
        public boolean available() { return true; }
        public String witness() { return witness; }
        public boolean matches(String expected) { return state.equals(expected); }
        public void consume(String after,String token) throws IOException { state=after; witness=token; writes++; if(crash) throw new IOException("native saved then disconnected"); }
    }
    @Test void unpublishedIntentNeverCreditsAndOnlyConsumedProofAllowsProductionPublish() throws Exception {
        var s=ready(); var p=prepare(s); var b=bank();
        assertEquals(InventoryIncome.Decision.WAIT,InventoryIncome.recover(p.inventory(),null));
        assertThrows(IllegalArgumentException.class,()->OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(0,b.balance(owner));
        var bag=new NativeBag(owner); assertEquals(InventoryIncome.Decision.CONSUMED,InventoryIncome.recover(p.inventory(),bag)); s.publish(p);
        assertTrue(OrdersFinance.deliver(s,new Gateway(b),owner,17)); assertEquals(1,bag.writes); assertEquals(17,b.balance(owner));
    }
    @Test void crashBetweenNativeSaveAndPublicationRecoversWitnessWithoutSecondConsumption() throws Exception {
        var s=ready(); var p=prepare(s); var bag=new NativeBag(owner); bag.crash=true;
        assertThrows(IOException.class,()->InventoryIncome.recover(p.inventory(),bag)); var reopened=load();
        bag.crash=false; assertEquals(InventoryIncome.Decision.CONSUMED,InventoryIncome.recover(reopened.pending().inventory(),bag)); reopened.publish(reopened.pending());
        var b=bank(); assertTrue(OrdersFinance.deliver(reopened,new Gateway(b),owner,17)); assertEquals(1,bag.writes); assertEquals(17,b.balance(owner)); assertEquals(1,load().completed(owner));
    }

}

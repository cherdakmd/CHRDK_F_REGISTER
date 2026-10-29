package ru.cherdakmd.chrdkhunt;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.MilestoneAwards;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящие source и BankStore; банковская арифметика не заменяется fake wallet. */
class HuntFinanceTest {
    @TempDir Path dir;
    final Logger log=Logger.getAnonymousLogger();
    final UUID owner=UUID.randomUUID();
    final long now=1_700_000_000_000L;
    HuntStore load() { var s=new HuntStore(dir,log); s.load(); return s; }
    HuntStore ready() { var s=load(); s.touchDay(owner,"Hunter",now); s.addCoins(owner,"Hunter",1000); s.addKeys(owner,"Hunter","undead",2); s.addShards(owner,"Hunter","undead",10); s.flush(); return s; }
    HuntStore earned() throws Exception { var s=ready(); assertTrue(s.exchangeToBank(owner,"Hunter",now,2,1,8,40)); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),log); b.load(); return b; }
    static final class Gateway implements MilestoneAwards.Bank {
        final BankStore bank; boolean online=true,known=true;
        Gateway(BankStore bank) { this.bank=bank; }
        public boolean available() { return online&&bank.available(); }
        public boolean registered(String s,UUID l) { return bank.incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return bank.registerIncomeSource(s,l); }
        public boolean known(UUID id) { return known; }
        public long credited(String s,UUID l,UUID id) { return bank.creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return bank.creditEntitlement(s,l,id.toString(),id,total); }
    }
    @Test void coinDebitDailyUsageAndIncomePublishTogether() throws Exception {
        earned(); var s=load(); assertEquals(920,s.coins(owner)); assertEquals(2,s.exchangeToday(owner,now)); assertEquals(2,s.income(owner)); assertEquals(2,s.keys(owner,"undead")); assertEquals(10,s.shards(owner,"undead"));
    }
    @Test void spendingAndRestartDoNotReissueOldExchange() throws Exception {
        var s=earned(); var b=bank(); assertTrue(HuntFinance.deliver(s,new Gateway(b),owner,2)); assertTrue(b.withdraw(owner,2)); b.flush();
        assertTrue(HuntFinance.deliver(load(),new Gateway(bank()),owner,2)); assertEquals(0,bank().balance(owner)); assertEquals(920,load().coins(owner));
    }
    @Test void nextExchangeAtChangedRateAddsOnlyNewDelta() throws Exception {
        var s=earned(); var b=bank(); HuntFinance.deliver(s,new Gateway(b),owner,2);
        assertTrue(s.exchangeToBank(owner,"Hunter",now,1,1,8,100)); assertEquals(820,load().coins(owner)); assertEquals(3,load().income(owner));
        assertTrue(HuntFinance.deliver(s,new Gateway(b),owner,3)); assertEquals(3,b.balance(owner));
    }
    @Test void closedMinDailyAndWalletRefusalsLeaveAllStateUntouched() throws Exception {
        var s=ready(); String before=Files.readString(s.file());
        assertFalse(s.exchangeToBank(owner,"Hunter",now,1,1,0,40));
        assertFalse(s.exchangeToBank(owner,"Hunter",now,1,2,8,40));
        assertFalse(s.exchangeToBank(owner,"Hunter",now,9,1,8,40));
        assertFalse(s.exchangeToBank(owner,"Hunter",now,1,1,8,100000));
        assertEquals(1000,s.coins(owner)); assertTrue(s.incomes().isEmpty()); assertEquals(before,Files.readString(s.file()));
    }
    @Test void minimumIsClampedToConfiguredDailyLimitAndUtcResetsUsageNotIncome() throws Exception {
        var s=ready(); assertTrue(s.exchangeToBank(owner,"Hunter",now,2,8,2,40));
        assertFalse(s.exchangeToBank(owner,"Hunter",now,1,1,2,40));
        assertTrue(s.exchangeToBank(owner,"Hunter",now+HuntStore.DAY_MILLIS,1,1,2,40)); assertEquals(1,load().exchangeToday(owner,now+HuntStore.DAY_MILLIS)); assertEquals(3,load().income(owner));
    }
    @Test void keyAndChestVirtualRewardsAndIncomePublishTogether() throws Exception {
        var s=ready(); assertTrue(s.openChestToBank(owner,"Hunter","undead",now,7,3,5)); s=load();
        assertEquals(1,s.keys(owner,"undead")); assertEquals(1007,s.coins(owner)); assertEquals(13,s.shards(owner,"undead")); assertEquals(5,s.income(owner)); assertEquals(0,s.exchangeToday(owner,now));
    }
    @Test void missingKeyCannotCreateIncomeOrVirtualRewards() throws Exception {
        var s=ready(); String before=Files.readString(s.file()); assertFalse(s.openChestToBank(owner,"Hunter","nether",now,7,3,5)); assertEquals(before,Files.readString(s.file())); assertTrue(s.incomes().isEmpty());
    }
    @Test void zeroDiamondChestStillConsumesExactlyOneKeyAndRetainsOtherRewards() throws Exception {
        var s=ready(); assertTrue(s.openChestToBank(owner,"Hunter","undead",now,7,3,0)); s=load(); assertEquals(1,s.keys(owner,"undead")); assertEquals(1007,s.coins(owner)); assertEquals(13,s.shards(owner,"undead")); assertTrue(s.incomes().isEmpty());
    }
    @Test void finalKeyCannotBeReusedForAnotherPayout() throws Exception {
        var s=ready(); assertTrue(s.openChestToBank(owner,"Hunter","undead",now,0,0,5)); assertTrue(s.openChestToBank(owner,"Hunter","undead",now,0,0,3)); assertFalse(s.openChestToBank(owner,"Hunter","undead",now,0,0,8));
        assertEquals(0,load().keys(owner,"undead")); assertEquals(8,load().income(owner));
    }
    @Test void exchangeAndChestShareOneCumulativeSourceWithoutSharingTheirLimits() throws Exception {
        var s=earned(); assertTrue(s.openChestToBank(owner,"Hunter","undead",now,0,0,5)); var b=bank(); assertTrue(HuntFinance.deliver(s,new Gateway(b),owner,7)); assertEquals(7,b.balance(owner)); assertEquals(2,load().exchangeToday(owner,now));
    }
    @Test void capAndPartialCapKeepTheUnpaidRemainder() throws Exception {
        var s=earned(); s.openChestToBank(owner,"Hunter","undead",now,0,0,5); var b=bank(); b.deposit(owner,"Hunter",BankStore.MAX_BALANCE); b.flush();
        assertFalse(HuntFinance.deliver(s,new Gateway(b),owner,7)); b.withdraw(owner,3); assertFalse(HuntFinance.deliver(s,new Gateway(b),owner,7)); assertEquals(BankStore.MAX_BALANCE,b.balance(owner));
        b.withdraw(owner,10); assertTrue(HuntFinance.deliver(load(),new Gateway(b),owner,7)); assertEquals(BankStore.MAX_BALANCE-6,b.balance(owner)); assertEquals(7,load().income(owner));
    }
    @Test void bankDownOrUnknownUuidKeepsDebtWithoutCreatingAccount() throws Exception {
        var s=earned(); var b=bank(); var gate=new Gateway(b); gate.online=false; assertFalse(HuntFinance.deliver(s,gate,owner,2)); assertFalse(b.knows(owner));
        gate.online=true; gate.known=false; assertFalse(HuntFinance.deliver(s,gate,owner,2)); assertFalse(b.knows(owner)); assertEquals(2,load().income(owner));
        gate.known=true; assertTrue(HuntFinance.deliver(load(),gate,owner,2)); assertEquals(2,b.balance(owner));
    }
    @Test void wrongLineageDoesNotRegisterReplacement() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(HuntStore.SOURCE,UUID.randomUUID()); assertThrows(IllegalStateException.class,()->HuntFinance.deliver(s,new Gateway(b),owner,2)); assertEquals(0,b.balance(owner));
    }
    @Test void lostRegistrationOfRegisteredSourceCannotLookLikeFreshInstall() throws Exception {
        var s=earned(); s.markRegistered(); var b=bank(); assertThrows(IllegalStateException.class,()->HuntFinance.deliver(s,new Gateway(b),owner,2)); assertEquals(0,b.balance(owner));
    }
    @Test void olderTotalAndUnbackedAmountsAreNotPayments() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(HuntStore.SOURCE,s.lineage()); s.markRegistered(); b.creditEntitlement(HuntStore.SOURCE,s.lineage(),owner.toString(),owner,3);
        assertThrows(IllegalStateException.class,()->HuntFinance.deliver(s,new Gateway(b),owner,2)); assertThrows(IllegalArgumentException.class,()->HuntFinance.deliver(s,new Gateway(b),owner,4)); assertEquals(3,b.balance(owner));
    }
    @Test void resetDoesNotBurnOwedIncomeOrReopenDailyExchange() throws Exception {
        var s=earned(); s.reset(owner); s.flush(); s=load(); assertNull(s.player(owner)); assertEquals(2,s.income(owner)); assertEquals(2,s.exchangeToday(owner,now));
        s.addCoins(owner,"Hunter",1000); assertFalse(s.exchangeToBank(owner,"Hunter",now,7,1,8,40)); assertTrue(s.exchangeToBank(owner,"Hunter",now,6,1,8,40));
        assertEquals(8,load().income(owner)); assertEquals(8,load().exchangeToday(owner,now)); var b=bank(); assertTrue(HuntFinance.deliver(s,new Gateway(b),owner,8)); assertEquals(8,b.balance(owner));
    }
    @Test void exchangeBeforeRenameErrorDoesNotDurablySpendCoinsOrUsage() throws Exception {
        ready(); var s=load(); Files.createDirectory(dir.resolve("hunt.json.tmp")); assertThrows(IOException.class,()->s.exchangeToBank(owner,"Hunter",now,2,1,8,40)); assertFalse(s.healthy()); s.flush();
        assertEquals(1000,load().coins(owner)); assertEquals(0,load().exchangeToday(owner,now)); assertTrue(load().incomes().isEmpty()); assertThrows(IllegalStateException.class,()->s.spendCoins(owner,1));
    }
    @Test void exchangeAfterRenameErrorRecoversDebitAndIncomeTogether() throws Exception {
        ready(); var s=new HuntStore(dir,log,()->{throw new IOException("after exchange replace");}); s.load(); assertThrows(IOException.class,()->s.exchangeToBank(owner,"Hunter",now,2,1,8,40)); assertFalse(s.healthy());
        assertEquals(920,load().coins(owner)); assertEquals(2,load().exchangeToday(owner,now)); assertEquals(2,load().income(owner));
    }
    @Test void chestBeforeRenameErrorLeavesKeyAndAllRewardsUnchanged() throws Exception {
        ready(); var s=load(); Files.createDirectory(dir.resolve("hunt.json.tmp")); assertThrows(IOException.class,()->s.openChestToBank(owner,"Hunter","undead",now,7,3,5)); s.flush();
        assertEquals(2,load().keys(owner,"undead")); assertEquals(1000,load().coins(owner)); assertEquals(10,load().shards(owner,"undead")); assertTrue(load().incomes().isEmpty());
    }
    @Test void chestAfterRenameErrorRecoversKeyVirtualRewardsAndIncomeTogether() throws Exception {
        ready(); var s=new HuntStore(dir,log,()->{throw new IOException("after chest replace");}); s.load(); assertThrows(IOException.class,()->s.openChestToBank(owner,"Hunter","undead",now,7,3,5));
        assertEquals(1,load().keys(owner,"undead")); assertEquals(1007,load().coins(owner)); assertEquals(13,load().shards(owner,"undead")); assertEquals(5,load().income(owner));
    }
    @Test void legacyImportPreservesGameAssetsWithoutPayingOldExchangeAgain() throws Exception {
        var s=ready(); s.recordExchange(owner,"Hunter",now,3); s.addTrophy(owner,"Hunter","undead_skull"); s.flush();
        var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); root.addProperty("version",1); root.remove("finance"); Files.writeString(s.file(),root.toString());
        s=load(); UUID lineage=s.lineage(); assertTrue(s.incomes().isEmpty()); assertEquals(3,s.exchangeToday(owner,now)); assertEquals(1000,s.coins(owner)); assertEquals(2,s.keys(owner,"undead")); assertEquals(10,s.shards(owner,"undead")); assertTrue(s.hasTrophy(owner,"undead_skull")); assertEquals(lineage,load().lineage());
    }
    @Test void invalidMetadataOrLegacyFinancialTypeCannotBeCleanedOnDisable() throws Exception {
        var s=earned(); String good=Files.readString(s.file());
        for(String bad:List.of(good.replace("\"hunt-income\"","\"orders-income\""),good.replace("\"registered\": false","\"registered\": \"false\""),good.replace("\"coins\": 920","\"coins\": \"920\""),good.replace("\"total\": 2","\"total\": 1"),"{broken")) {
            Files.writeString(s.file(),bad); var rejected=new HuntStore(dir,log); assertThrows(IllegalStateException.class,rejected::load); rejected.flush(); assertEquals(bad,Files.readString(s.file()));
        }
    }
    @Test void invalidQuotesAndCumulativeOverflowFailBeforeResourceConsumption() throws Exception {
        var s=ready(); assertThrows(IllegalArgumentException.class,()->s.exchangeToBank(owner,"Hunter",now,1,1,8,0)); assertThrows(IllegalArgumentException.class,()->s.openChestToBank(owner,"Hunter","undead",now,0,0,9)); assertEquals(1000,s.coins(owner)); assertEquals(2,s.keys(owner,"undead"));
        var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); root.getAsJsonObject("finance").getAsJsonObject("incomes").addProperty(owner.toString(),Long.MAX_VALUE); Files.writeString(s.file(),root.toString()); var full=load();
        assertThrows(ArithmeticException.class,()->full.exchangeToBank(owner,"Hunter",now,1,1,8,40)); assertThrows(ArithmeticException.class,()->full.openChestToBank(owner,"Hunter","undead",now,0,0,1)); assertEquals(1000,full.coins(owner)); assertEquals(2,full.keys(owner,"undead"));
    }
    @Test void asyncSaveAndFinancialCommitUseTheSameMonitor() throws Exception {
        ready(); var ref=new java.util.concurrent.atomic.AtomicReference<HuntStore>(); var held=new java.util.concurrent.atomic.AtomicBoolean();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1); var started=new java.util.concurrent.CountDownLatch(1); var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var s=new HuntStore(dir,log,()->{if(Thread.currentThread().getName().equals("old-hunt-save")) {
            held.set(Thread.holdsLock(ref.get())); entered.countDown();
            try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }}); ref.set(s); s.load(); s.addShards(owner,"Hunter","undead",1);
        var writer=new Thread(s::saveNow,"old-hunt-save"); var transaction=new Thread(()->{started.countDown();try {s.exchangeToBank(owner,"Hunter",now,2,1,8,40);} catch(Throwable e) {error.set(e);}}); writer.start();
        try { assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(held.get()); transaction.start(); assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS)); } finally {release.countDown();}
        writer.join(5000); transaction.join(5000); assertFalse(writer.isAlive()); assertFalse(transaction.isAlive()); assertNull(error.get()); assertEquals(920,load().coins(owner)); assertEquals(2,load().income(owner)); assertEquals(11,load().shards(owner,"undead"));
    }
    @Test void serviceUsesAtomicMethodsBeforeLootAndHasNoPhysicalMoneyFallback() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntService.java"));
        assertFalse(source.contains("payDiamonds")); assertFalse(source.contains("Material.DIAMOND")); assertTrue(source.contains("plugin.finance().exchange(player,config,now,requested)")); assertTrue(source.indexOf("plugin.finance().chest(player,family,now,coins,result)")<source.indexOf("for (HuntDrop drop : result.drops())"));
        assertTrue(source.contains("plugin.finance().eligible(killer)")); assertTrue(source.contains("giveItems(player, drop.material()"));
    }
}

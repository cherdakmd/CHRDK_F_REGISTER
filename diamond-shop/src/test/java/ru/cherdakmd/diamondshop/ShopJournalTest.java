package ru.cherdakmd.diamondshop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ShopExchange;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ShopJournalTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(); final long day=86400000L*21500,time=day+86399000L;
    final Logger log=Logger.getAnonymousLogger();
    final ShopConfig.ShopItem item=new ShopConfig.ShopItem("STONE","blocks",1,1000,.25,4);
    ShopStore load() { var s=new ShopStore(dir,log); s.load(); return s; }
    ShopStore initial() { var s=load(); s.setStock(item,1000,time); assertTrue(s.ensureMarket()); assertTrue(s.markRegistered()); return s; }
    ShopExchange trade(ShopStore s,boolean buy) { return new ShopExchange(UUID.randomUUID(),s.market(),s.sequence()+1,owner,buy,"STONE",64,10,buy?10:0,buy?0:10,0,Map.of("DIAMOND",1),"before","after"); }
    ShopJournal.Pending pending(boolean buy) { var s=initial(); assertTrue(s.prepare(trade(s,buy),item,time,0,100,24,3600000,1)); return load().pending(); }
    static class Bank implements ShopJournal.Bank {
        ShopExchange.Status state=ShopExchange.Status.MISSING; int commits,grants; boolean fail;
        public ShopExchange.Status status(ShopExchange t) { return state; }
        public ShopExchange.Status commit(ShopExchange t) { commits++; if(fail) {state=ShopExchange.Status.PREPARED; return state;} if(state!=ShopExchange.Status.COMMITTED) grants++; return state=ShopExchange.Status.COMMITTED; }
    }
    @Test void sourceIntentIsDurableBeforeInvokingBankAndDoesNotChangeMarketYet() { var p=pending(true); var s=load(); assertNotNull(p); assertEquals(0,s.sequence()); assertEquals(1000,s.snapshotOf("STONE").stock); assertEquals(0,s.turnover()); assertEquals(0,s.usedToday(owner,time)); }
    @Test void completeBuyAppliesStockStatsQuotaAndHistoryExactlyOnce() { var p=pending(true); var bank=new Bank(); assertTrue(ShopJournal.recover(load(),bank)); var s=load(); assertNull(s.pending()); assertEquals(1,s.sequence()); assertEquals(936,s.snapshotOf("STONE").stock); assertEquals(64,s.snapshotOf("STONE").boughtByPlayers); assertEquals(10,s.turnover()); assertEquals(10,s.usedToday(owner,time)); assertArrayEquals(new double[]{1},s.historyOf("STONE")); assertTrue(ShopJournal.recover(load(),bank)); assertEquals(1,bank.grants); assertEquals(1,bank.commits); assertTrue(s.finish(p)); assertEquals(10,load().turnover()); }
    @Test void saleUsesSameJournalWithOppositeStockDirection() { pending(false); assertTrue(ShopJournal.recover(load(),new Bank())); var s=load(); assertEquals(1064,s.snapshotOf("STONE").stock); assertEquals(64,s.snapshotOf("STONE").soldByPlayers); assertEquals(0,s.snapshotOf("STONE").boughtByPlayers); }
    @Test void preparedBankAfterCrashIsRetriedWithoutNewSourceTransaction() { var p=pending(true); var bank=new Bank(); bank.fail=true; assertFalse(ShopJournal.recover(load(),bank)); assertEquals(p,load().pending()); assertEquals(1000,load().snapshotOf("STONE").stock); bank.fail=false; assertTrue(ShopJournal.recover(load(),bank)); assertEquals(1,bank.grants); assertEquals(1,load().sequence()); }
    @Test void bankReceiptAfterNativeGrantCanCompleteSourceWithoutPlayerOrSecondCommit() { pending(true); var bank=new Bank(); bank.state=ShopExchange.Status.COMMITTED; assertTrue(ShopJournal.recover(load(),bank)); assertEquals(0,bank.commits); assertEquals(936,load().snapshotOf("STONE").stock); }
    @Test void unknownBankDoesNotMeanMissingAndDoesNotConsumeSourceObligation() { var p=pending(true); var bank=new Bank(); bank.state=ShopExchange.Status.UNAVAILABLE; assertFalse(ShopJournal.recover(load(),bank)); assertEquals(0,bank.commits); assertEquals(p,load().pending()); assertEquals(0,load().sequence()); }
    @Test void conflictQuarantinesSourceRatherThanGuessingRefundOrRegrant() throws Exception { pending(true); String before=Files.readString(dir.resolve("shop-data.json")); var bank=new Bank(); bank.state=ShopExchange.Status.CONFLICT; var s=load(); assertFalse(ShopJournal.recover(s,bank)); assertFalse(s.legacyAvailable()); s.saveNow(); assertEquals(before,Files.readString(dir.resolve("shop-data.json"))); assertEquals(0,bank.commits); }
    @Test void preRenameSourcePrepareFailureCannotInvokeBank() throws Exception { var s=initial(); Files.createDirectory(dir.resolve("shop-data.json.tmp")); assertFalse(s.prepare(trade(s,true),item,time,0,100,24,3600000,1)); assertFalse(s.legacyAvailable()); assertNull(load().pending()); assertEquals(0,load().sequence()); }
    @Test void afterRenameSourcePrepareFailureRecoversPersistedIntent() { initial(); var s=new ShopStore(dir,log,()->{throw new IOException("prepare uncertain");}); s.load(); assertFalse(s.prepare(trade(s,true),item,time,0,100,24,3600000,1)); assertFalse(s.legacyAvailable()); s.saveNow(); assertNotNull(load().pending()); assertTrue(ShopJournal.recover(load(),new Bank())); assertEquals(936,load().snapshotOf("STONE").stock); }
    @Test void beforeRenameSourceFinishFailureRetainsIntentWhileBankAlreadyCommitted() throws Exception { pending(true); Files.createDirectory(dir.resolve("shop-data.json.tmp")); var s=load(); var bank=new Bank(); assertFalse(ShopJournal.recover(s,bank)); assertFalse(s.legacyAvailable()); assertNotNull(load().pending()); assertEquals(1000,load().snapshotOf("STONE").stock); Files.delete(dir.resolve("shop-data.json.tmp")); assertTrue(ShopJournal.recover(load(),bank)); assertEquals(1,bank.grants); assertEquals(1,bank.commits); assertEquals(936,load().snapshotOf("STONE").stock); }
    @Test void afterRenameFinishFailureCannotRepeatMarketEffectOnRestart() { pending(true); var s=new ShopStore(dir,log,()->{throw new IOException("finish uncertain");}); s.load(); var bank=new Bank(); assertFalse(ShopJournal.recover(s,bank)); assertFalse(s.legacyAvailable()); s.saveNow(); assertNull(load().pending()); assertEquals(1,load().sequence()); assertEquals(10,load().turnover()); assertTrue(ShopJournal.recover(load(),bank)); assertEquals(1,bank.grants); assertEquals(936,load().snapshotOf("STONE").stock); }
    @Test void runtimeFailureAfterFinishRenameHasSameQuarantineSemantics() { pending(true); var s=new ShopStore(dir,log,()->{throw new IllegalStateException("runtime uncertain");}); s.load(); assertFalse(ShopJournal.recover(s,new Bank())); assertFalse(s.legacyAvailable()); assertEquals(1,load().sequence()); assertNull(load().pending()); }
    @Test void settlementAfterMidnightBooksTheAcceptedUtcDayNotRecoveryDay() { pending(true); var s=load(); assertTrue(ShopJournal.recover(s,new Bank())); assertEquals(10,s.usedToday(owner,time)); assertEquals(0,s.usedToday(owner,day+86400000)); assertEquals(10,load().usedToday(owner,time)); }
    @Test void frozenHistoryAndEffectSurviveConfigReloadAndAreNotRequoted() { var p=pending(true); var s=load(); s.recordPrice(item,999,time+1000,4,0); assertArrayEquals(new double[0],s.historyOf("STONE")); assertEquals(p,s.pending()); assertEquals(1000,s.stockOf(item,time+86400000,1)); assertTrue(ShopJournal.recover(s,new Bank())); assertArrayEquals(new double[]{1},load().historyOf("STONE")); }
    @Test void pendingBlocksOtherTransactionsAdminStockAndQuotaInterleaving() { pending(true); var s=load(); assertFalse(s.prepare(trade(s,false),item,time,0,100,24,3600000,1)); assertThrows(IllegalStateException.class,()->s.setStock(item,123,time)); assertThrows(IllegalStateException.class,()->s.adjustStock(item,1,time,0)); assertThrows(IllegalStateException.class,()->s.recordTrade(item,1,true,1)); assertFalse(s.tryRecordTurnover(UUID.randomUUID(),1,100,time)); }
    @Test void disabledLimitKeepsPriorCompatibilityButStillJournalsGlobalTurnover() { var s=initial(); assertTrue(s.prepare(trade(s,true),item,time,0,0,24,3600000,1)); assertEquals(-1,s.pending().used()); assertTrue(ShopJournal.recover(s,new Bank())); assertEquals(10,load().turnover()); assertEquals(0,load().usedToday(owner,time)); }
    @Test void capQuotaAndCounterOverflowRefuseBeforePreparingAnyIntent() throws Exception { var s=initial(); assertFalse(s.prepare(trade(s,true),item,time,0,9,24,3600000,1)); assertNull(s.pending()); var json=JsonParser.parseString(s.snapshotJson()).getAsJsonObject(); json.getAsJsonObject("items").getAsJsonObject("STONE").addProperty("boughtByPlayers",Long.MAX_VALUE); Files.writeString(dir.resolve("shop-data.json"),json.toString()); s=load(); assertFalse(s.prepare(trade(s,true),item,time,0,100,24,3600000,1)); assertNull(s.pending()); }
    @Test void schemaMigrationKeepsLegacyExactValuesAndMakesLineageStableBeforeBankRegistration() throws Exception { Files.writeString(dir.resolve("shop-data.json"),"{\"balances\":{\""+owner+"\":1.000000000000000000001}}"); var s=load(); assertTrue(s.isDirty()); assertTrue(s.ensureMarket()); assertEquals(s.market(),load().market()); assertEquals(2,load().legacyAmount(owner)); }
    @Test void noMetadataDowngradeCanErasePendingJournal() throws Exception { pending(true); var json=JsonParser.parseString(Files.readString(dir.resolve("shop-data.json"))).getAsJsonObject(); json.remove("schema"); String bad=json.toString(); Files.writeString(dir.resolve("shop-data.json"),bad); var s=load(); assertFalse(s.legacyAvailable()); s.saveNow(); assertEquals(bad,Files.readString(dir.resolve("shop-data.json"))); }
    @Test void tamperedEffectSequenceOrTypedAmountsStopReaderWithoutRewrite() throws Exception { pending(true); String original=Files.readString(dir.resolve("shop-data.json")); for(String field:List.of("stock","sold","bought","time","day","historyPoints")) for(String value:List.of("\"1\"","1.5","9223372036854775808")) { var json=JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("pending").add(field,JsonParser.parseString(value)); String bad=json.toString(); Files.writeString(dir.resolve("shop-data.json"),bad); var s=load(); assertFalse(s.legacyAvailable()); s.saveNow(); assertEquals(bad,Files.readString(dir.resolve("shop-data.json"))); } }
    @Test void productionUsesJournalAndAuthRecoveryNotTheOldCallback() throws Exception { String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java")); assertFalse(source.contains("exchangeDiamonds(")); assertFalse(source.contains("dropItem")); assertFalse(source.contains("store.adjustStock(")); assertTrue(source.contains("store.prepare(t,item")); assertTrue(source.contains("ShopJournal.recover(store")); assertTrue(source.contains("core.legacyShopImported(id)")); String plugin=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/DiamondShopPlugin.java")); assertTrue(plugin.contains("EventPriority.LOWEST")); assertTrue(plugin.contains("runTaskTimer(this,()->shopService.recoverPending()")); assertTrue(plugin.contains("<23")); }
    @Test void healthyMissingAfterRefusedIntakeCancelsOnlyUnacceptedSourceRequest() {
        pending(true); var bank=new Bank() { @Override public ShopExchange.Status commit(ShopExchange t) { return ShopExchange.Status.UNAVAILABLE; } };
        assertFalse(ShopJournal.recover(load(),bank)); var s=load(); assertNull(s.pending()); assertEquals(0,s.sequence()); assertEquals(1000,s.snapshotOf("STONE").stock); assertEquals(0,s.turnover()); assertEquals(0,s.usedToday(owner,time)); assertTrue(s.legacyAvailable());
    }
    @Test void unavailableAfterIntakeCannotBeMisreadAsSafeToCancel() {
        var p=pending(true); var bank=new Bank() { @Override public ShopExchange.Status commit(ShopExchange t) {state=ShopExchange.Status.UNAVAILABLE; return state;} };
        assertFalse(ShopJournal.recover(load(),bank)); assertEquals(p,load().pending());
    }
    @Test void disappearanceOfAnAcknowledgedRootIntentStopsSourceInsteadOfCancelling() {
        pending(true); var bank=new Bank() { @Override public ShopExchange.Status commit(ShopExchange t) {state=ShopExchange.Status.MISSING; return ShopExchange.Status.UNAVAILABLE;} }; bank.state=ShopExchange.Status.PREPARED;
        var s=load(); assertFalse(ShopJournal.recover(s,bank)); assertFalse(s.legacyAvailable()); assertNotNull(load().pending());
    }

    @Test void registeredMarkerIsDurableAndAnUnregisteredSourceCannotPrepareMoney() {
        var s=load(); s.setStock(item,1000,time); assertTrue(s.ensureMarket()); assertFalse(s.registered()); assertFalse(s.prepare(trade(s,true),item,time,0,100,24,3600000,1));
        assertTrue(s.markRegistered()); assertTrue(load().registered());
    }
    @Test void registrationMarkerWriteFailureDoesNotPermitAnyTradeIntake() {
        var s=new ShopStore(dir,log,()->{throw new IOException("registration uncertain");});
        assertFalse(s.markRegistered()); assertFalse(s.legacyAvailable()); assertFalse(s.registered()); assertNull(s.pending());
        assertTrue(load().registered());
    }
    @Test void sourceBindingNeverRegistersAgainAfterPriorAcknowledgement() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        assertTrue(source.contains("if(store.registered()) return core.shopRegistered(store.market());")); assertTrue(source.contains("if(!binding()) return false;"));
    }

    @Test void partialQuantityAndRoundedWholeSumRecoverWithoutRequoting() {var s=initial();long units=17,money=TradeAmounts.quote(64,3,units,true);var t=new ShopExchange(UUID.randomUUID(),s.market(),1,owner,true,"STONE",units,money,money,0,0,Map.of("DIAMOND",1),"before","after");assertTrue(s.prepare(t,item,time,0,100,24,3600000,1));assertEquals(17,load().pending().trade().units());assertEquals(1,load().pending().trade().money());var bank=new Bank();assertTrue(ShopJournal.recover(load(),bank));assertEquals(983,load().snapshotOf("STONE").stock);assertEquals(17,load().snapshotOf("STONE").boughtByPlayers);assertEquals(1,load().usedToday(owner,time));assertTrue(ShopJournal.recover(load(),bank));assertEquals(1,bank.grants);}
    @Test void partialSaleRetainsFrozenQuantityAndDoesNotReplayWhenBankIsUnavailable() {var s=initial();long units=TradeAmounts.saleAll(64,3,63,2);long money=TradeAmounts.quote(64,3,units,false);var t=new ShopExchange(UUID.randomUUID(),s.market(),1,owner,false,"STONE",units,money,0,money,0,Map.of("DIAMOND",1),"before","after");assertTrue(s.prepare(t,item,time,0,100,24,3600000,1));var bank=new Bank();bank.state=ShopExchange.Status.UNAVAILABLE;assertFalse(ShopJournal.recover(load(),bank));assertEquals(43,load().pending().trade().units());bank.state=ShopExchange.Status.MISSING;assertTrue(ShopJournal.recover(load(),bank));assertEquals(1043,load().snapshotOf("STONE").stock);assertEquals(43,load().snapshotOf("STONE").soldByPlayers);assertEquals(2,load().turnover());}
    @Test void zeroMoneyPartialSaleCannotPrepareAnySourceIntent(){var s=initial();var t=new ShopExchange(UUID.randomUUID(),s.market(),1,owner,false,"STONE",1,0,0,0,0,Map.of("DIAMOND",1),"before","after");assertFalse(s.prepare(t,item,time,0,100,24,3600000,1));assertNull(load().pending());assertEquals(1000,load().snapshotOf("STONE").stock);}

}

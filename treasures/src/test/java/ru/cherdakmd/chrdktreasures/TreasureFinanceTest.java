package ru.cherdakmd.chrdktreasures;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.MilestoneAwards;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Реальные source и BankStore; native-границу проверяет также контракт production call sites. */
class TreasureFinanceTest {
    @TempDir Path dir;
    final Logger log=Logger.getAnonymousLogger();
    final UUID owner=UUID.randomUUID();
    final long now=1_700_000_000_000L;
    TreasureStore load() { var s=new TreasureStore(dir,log); s.load(); return s; }
    TreasuresConfig config(int rank,int... thresholds) {
        var y=new YamlConfiguration(); y.set("ranks.seeker.diamonds",rank); y.set("milestones.20.diamonds",8);
        for(int i=0;i<thresholds.length;i+=2) y.set("milestones."+thresholds[i]+".diamonds",thresholds[i+1]);
        return TreasuresConfig.parse(y,log);
    }
    void find(TreasureStore s,String item,TreasuresConfig c) throws IOException { s.recordRewardedTreasure(owner,TreasureTier.COMMON,item,now,600000,c); }
    TreasureStore ready() { var s=load(); for(int i=0;i<4;i++) s.recordTreasure(owner.toString(),TreasureTier.COMMON,null,now,600000); s.addShards(owner.toString(),10); s.flush(); return s; }
    TreasureStore earned() throws Exception { var s=ready(); find(s,null,config(4)); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),log); b.load(); return b; }
    static final class Gateway implements MilestoneAwards.Bank {
        final BankStore b; boolean online=true,known=true;
        Gateway(BankStore b) { this.b=b; }
        public boolean available() { return online&&b.available(); }
        public boolean registered(String s,UUID l) { return b.incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return b.registerIncomeSource(s,l); }
        public boolean known(UUID id) { return known; }
        public long credited(String s,UUID l,UUID id) { return b.creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return b.creditEntitlement(s,l,id.toString(),id,total); }
    }
    boolean deliver(TreasureStore s,BankStore b) throws IOException { return TreasureFinance.deliver(s,new Gateway(b),owner,s.income(owner)); }
    @Test void rankProgressAndFrozenIncomeAreDurableTogether() throws Exception {
        earned(); var s=load(); assertEquals(5,s.found(owner.toString())); assertEquals(4,s.income(owner)); assertEquals(Map.of("rank:seeker",4L),s.claims(owner)); assertEquals(10,s.shards(owner.toString()));
    }
    @Test void collectionCountsDistinctItemsNotRepeatedFinds() throws Exception {
        var s=load(); var c=config(4,2,7); find(s,"one",c); find(s,"one",c); assertEquals(0,s.income(owner)); find(s,"two",c);
        s=load(); assertEquals(3,s.found(owner.toString())); assertEquals(2,s.foundItems(owner.toString())); assertEquals(7,s.income(owner)); assertEquals(2,s.itemCount(owner.toString(),"one"));
    }
    @Test void sameFindCommitsRankAndCollectionWithoutSeparateAwardFiles() throws Exception {
        var s=ready(); find(s,"new",config(4,1,7)); s=load(); assertEquals(11,s.income(owner)); assertEquals(1,s.foundItems(owner.toString())); assertEquals(2,s.claims(owner).size());
    }
    @Test void changedPricesDoNotRequoteOldClaimButNewThresholdIsIndependent() throws Exception {
        var s=load(); find(s,"one",config(4,1,7)); assertFalse(s.reconcile(owner,config(40,1,70))); assertEquals(7,s.income(owner));
        find(s,"two",config(40,1,70,2,9)); assertEquals(16,load().income(owner)); assertEquals(7,load().frozen(owner,"collection:1"));
    }
    @Test void zeroRewardIsAPermanentClaimEvenAfterResetAndConfigChange() throws Exception {
        var s=ready(); find(s,"one",config(0,1,0)); s=load(); assertEquals(0,s.income(owner)); assertEquals(2,s.claims(owner).size());
        s.reset(owner.toString()); for(int i=0;i<5;i++) find(s,"one",config(40,1,70)); s.flush(); assertEquals(0,load().income(owner));
    }
    @Test void resetDoesNotBurnDebtOrReopenAlreadyClaimedRank() throws Exception {
        var s=earned(); s.reset(owner.toString()); for(int i=0;i<5;i++) find(s,null,config(40)); s.flush(); s=load(); assertEquals(4,s.income(owner));
        var b=bank(); assertTrue(deliver(s,b)); assertEquals(4,b.balance(owner));
    }
    @Test void resetAllRetainsClaimsAndDebtWithoutAPlayerProfile() throws Exception {
        var s=earned(); assertEquals(1,s.resetAll()); s.flush(); s=load(); assertEquals(0,s.found(owner.toString())); assertEquals(4,s.income(owner)); assertTrue(deliver(s,bank()));
    }
    @Test void legacyMigrationClosesOldRanksAndExplicitMilestonesWithoutRetroPay() throws Exception {
        var s=ready(); s.recordTreasure(owner.toString(),TreasureTier.RARE,"old"); s.markMilestone(owner.toString(),1); s.setAnnounce(owner.toString(),false); s.flush();
        var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); root.addProperty("version",1); root.remove("finance");root.remove("loot_totals"); Files.writeString(s.file(),root.toString());
        s=load(); assertEquals(2,s.claims(owner).size()); assertEquals(0,s.income(owner)); assertEquals(10,s.shards(owner.toString())); assertEquals(1,s.itemCount(owner.toString(),"old")); assertFalse(s.announce(owner.toString()));
        assertFalse(s.reconcile(owner,config(40,1,70))); assertEquals(0,load().income(owner)); assertEquals(3,JsonParser.parseString(Files.readString(s.file())).getAsJsonObject().get("version").getAsInt());
    }
    @Test void missingLegacyMarkerMayOnlyCreateANewIndependentEligibleClaim() throws Exception {
        var s=ready(); s.recordTreasure(owner.toString(),TreasureTier.RARE,"old"); s.flush(); var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); root.addProperty("version",1); root.remove("finance");root.remove("loot_totals"); Files.writeString(s.file(),root.toString());
        s=load(); assertTrue(s.reconcile(owner,config(40,1,7))); assertEquals(7,s.income(owner)); assertEquals(0,s.frozen(owner,"rank:seeker"));
    }
    @Test void malformedLegacyTypesRefuseWithoutOverwritingOriginal() throws Exception {
        var s=ready(); var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); root.addProperty("version",1); root.remove("finance");root.remove("loot_totals");
        for(String invalid:List.of("-1","1.5","\"2\"","true","9223372036854775808")) {
            var copy=root.deepCopy(); copy.getAsJsonObject("players").getAsJsonObject(owner.toString()).add("mined",JsonParser.parseString(invalid)); refuse(copy.toString());
        }
        var copy=root.deepCopy(); copy.getAsJsonObject("players").getAsJsonObject(owner.toString()).remove("milestones"); refuse(copy.toString());
    }
    void refuse(String original) throws Exception {
        Files.writeString(dir.resolve("treasures.json"),original); var s=new TreasureStore(dir,log); assertThrows(IllegalStateException.class,s::load); assertFalse(s.healthy()); s.flush(); assertEquals(original,Files.readString(s.file()));
    }
    @Test void strictFinancialMetadataRejectsInvalidNamespaceLineageKeysAmountsAndMissingRows() throws Exception {
        var s=earned(); var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject();
        for(String field:List.of("source","lineage","registered","awards")) { var copy=root.deepCopy(); copy.getAsJsonObject("finance").remove(field); refuse(copy.toString()); }
        for(String amount:List.of("-1","1.5","\"4\"","577")) {
            var copy=root.deepCopy(); copy.getAsJsonObject("finance").getAsJsonObject("awards").getAsJsonObject(owner.toString()).add("rank:seeker",JsonParser.parseString(amount)); refuse(copy.toString());
        }
        var copy=root.deepCopy(); copy.getAsJsonObject("finance").addProperty("source","other"); refuse(copy.toString());
        copy=root.deepCopy(); copy.getAsJsonObject("finance").addProperty("lineage","1-1-1-1-1"); refuse(copy.toString());
        copy=root.deepCopy(); copy.getAsJsonObject("finance").getAsJsonObject("awards").getAsJsonObject(owner.toString()).addProperty("rank:novice",4); refuse(copy.toString());
    }
    @Test void duplicateFieldsTrailingGarbageAndNoncanonicalOwnersAreNotLenientlyRepaired() throws Exception {
        refuse("{\"version\":1,\"version\":2,\"players\":{}}"); refuse("{\"version\":1,\"players\":{}} trailing"); refuse("{version:1,players:{}}");
        var s=new TreasureStore(dir.resolve("good"),log); s.load(); s.recordTreasure(owner.toString(),TreasureTier.COMMON,null); s.flush();
        refuse(Files.readString(s.file()).replace(owner.toString(),"1-1-1-1-1"));
    }
    @Test void beforeRenameFailureLeavesOldProgressAndNoIncomeAndStopsFurtherWrites() throws Exception {
        var s=ready(); Files.createDirectory(dir.resolve("treasures.json.tmp")); assertThrows(IOException.class,()->find(s,null,config(4))); assertFalse(s.healthy()); s.flush();
        assertEquals(4,load().found(owner.toString())); assertEquals(0,load().income(owner)); assertThrows(IllegalStateException.class,()->s.resetAll());
    }
    @Test void afterRenameFailureRecoversProgressAndIncomeTogether() throws Exception {
        ready(); var s=new TreasureStore(dir,log,()->{throw new IOException("after replace");}); s.load(); assertThrows(IOException.class,()->find(s,null,config(4))); assertFalse(s.healthy()); s.flush(); assertEquals(5,load().found(owner.toString())); assertEquals(4,load().income(owner));
    }
    @Test void runtimeIoAmbiguityAlsoQuarantinesWithoutDestructiveShutdown() throws Exception {
        ready(); var s=new TreasureStore(dir,log,()->{throw new IllegalStateException("after replace");}); s.load(); assertThrows(IllegalStateException.class,()->find(s,null,config(4))); assertFalse(s.healthy()); s.flush(); assertEquals(4,load().income(owner));
    }
    @Test void spendingAndRestartNeverReissuePaidRanks() throws Exception {
        var s=earned(); var b=bank(); assertTrue(deliver(s,b)); assertTrue(b.withdraw(owner,4)); b.flush(); assertTrue(deliver(load(),bank())); assertEquals(0,bank().balance(owner));
    }
    @Test void fullAndPartialCapRetainRemainingDebt() throws Exception {
        var s=earned(); var b=bank(); b.deposit(owner,"Hunter",BankStore.MAX_BALANCE); b.flush(); assertFalse(deliver(s,b)); b.withdraw(owner,1); assertFalse(deliver(s,b)); assertEquals(BankStore.MAX_BALANCE,b.balance(owner));
        b.withdraw(owner,10); assertTrue(deliver(load(),b)); assertEquals(BankStore.MAX_BALANCE-7,b.balance(owner)); assertEquals(4,load().income(owner));
    }
    @Test void unavailableBankQueuesNewAwardsAndLaterDelivers() throws Exception {
        var s=earned(); var b=bank(); var g=new Gateway(b); g.online=false; assertFalse(TreasureFinance.deliver(s,g,owner,4)); assertFalse(b.knows(owner)); assertEquals(4,load().income(owner));
        g.online=true; assertTrue(TreasureFinance.deliver(load(),g,owner,4)); assertEquals(4,b.balance(owner));
    }
    @Test void unknownUuidWaitsWithoutCreatingAnAccount() throws Exception {
        var s=earned(); var b=bank(); var g=new Gateway(b); g.known=false; assertFalse(TreasureFinance.deliver(s,g,owner,4)); assertFalse(b.knows(owner)); assertEquals(4,load().income(owner));
        g.known=true; assertTrue(TreasureFinance.deliver(load(),g,owner,4)); assertEquals(4,b.balance(owner));
    }
    @Test void wrongLineageCannotRegisterAReplacementSource() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(TreasureStore.SOURCE,UUID.randomUUID()); assertThrows(IllegalStateException.class,()->deliver(s,b)); assertEquals(0,b.balance(owner));
    }
    @Test void lostRegisteredNamespaceCannotBecomeFreshInstall() throws Exception {
        var s=earned(); s.markRegistered(); var b=bank(); assertThrows(IllegalStateException.class,()->deliver(s,b)); assertEquals(0,b.balance(owner));
    }
    @Test void receiptAheadOfSourceRefusesRatherThanSilentlyPayingAgain() throws Exception {
        var s=earned(); var b=bank(); b.registerIncomeSource(TreasureStore.SOURCE,s.lineage()); s.markRegistered(); b.creditEntitlement(TreasureStore.SOURCE,s.lineage(),owner.toString(),owner,5);
        assertThrows(IllegalStateException.class,()->deliver(s,b)); assertEquals(5,b.balance(owner));
    }
    @Test void callerCannotSupplyAnUnbackedTotal() throws Exception {
        var s=earned(); var b=bank(); assertThrows(IllegalArgumentException.class,()->TreasureFinance.deliver(s,new Gateway(b),owner,5)); assertEquals(0,b.balance(owner));
    }
    @Test void reconciliationUsesSavedProgressAndNewConfigWithoutReplayingOldClaims() throws Exception {
        var s=load(); s.recordTreasure(owner.toString(),TreasureTier.COMMON,"one"); s.recordTreasure(owner.toString(),TreasureTier.COMMON,"two"); s.flush(); s=load(); assertTrue(s.reconcile(owner,config(4,2,9))); assertFalse(s.reconcile(owner,config(4,2,99))); assertEquals(9,load().income(owner)); assertEquals(2,load().foundItems(owner.toString()));
    }
    @Test void snapshotIsNotADurableAcknowledgement() throws Exception {
        var s=ready(); s.addShards(owner.toString(),1); s.snapshotJson(); assertTrue(s.isDirty()); s.saveNow(); assertFalse(s.isDirty()); assertEquals(11,load().shards(owner.toString()));
    }
    @Test void invalidRewardPlanFailsBeforeProgressMutation() throws Exception {
        var s=ready(); assertThrows(IllegalArgumentException.class,()->find(s,"bad",null)); assertEquals(4,s.found(owner.toString())); assertEquals(0,s.foundItems(owner.toString())); assertEquals(0,s.income(owner));
    }
    @Test void progressAndShardCountersSaturateWithoutWrappingIntoNegativeOrReopeningRewards() throws Exception {
        var s=ready(); var root=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject(); var row=root.getAsJsonObject("players").getAsJsonObject(owner.toString()); row.addProperty("mined",Long.MAX_VALUE); row.addProperty("shards",Long.MAX_VALUE); row.addProperty("shards_spent",Long.MAX_VALUE); row.addProperty("streak",Integer.MAX_VALUE); row.addProperty("best_streak",Integer.MAX_VALUE); row.getAsJsonObject("tiers").addProperty("common",Long.MAX_VALUE); row.getAsJsonObject("items").addProperty("one",Integer.MAX_VALUE); Files.writeString(s.file(),root.toString());
        s=load(); s.addMined(owner.toString(),1); s.addShards(owner.toString(),1); assertTrue(s.takeShards(owner.toString(),1)); find(s,"one",config(4)); s=load(); assertEquals(Long.MAX_VALUE,s.mined(owner.toString())); assertEquals(Long.MAX_VALUE,s.found(owner.toString())); assertEquals(Integer.MAX_VALUE,s.itemCount(owner.toString(),"one")); assertEquals(Integer.MAX_VALUE,s.streak(owner.toString())); assertEquals(Long.MAX_VALUE-1,s.shards(owner.toString())); assertEquals(Long.MAX_VALUE,s.shardsSpent(owner.toString())); assertEquals(154,s.income(owner));
    }
    @Test void asyncSaveAndFinancialCommitHoldTheSameMonitor() throws Exception {
        ready(); var ref=new java.util.concurrent.atomic.AtomicReference<TreasureStore>(); var held=new java.util.concurrent.atomic.AtomicBoolean();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1); var started=new java.util.concurrent.CountDownLatch(1); var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var s=new TreasureStore(dir,log,()->{if(Thread.currentThread().getName().equals("old-treasure-save")) {
            held.set(Thread.holdsLock(ref.get())); entered.countDown();
            try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }}); ref.set(s); s.load(); s.addShards(owner.toString(),1);
        var writer=new Thread(s::saveNow,"old-treasure-save"); var transaction=new Thread(()->{started.countDown();try { find(s,null,config(4)); } catch(Throwable e) {error.set(e);}}); writer.start();
        try { assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(held.get()); transaction.start(); assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS)); } finally { release.countDown(); }
        writer.join(5000); transaction.join(5000); assertFalse(writer.isAlive()); assertFalse(transaction.isAlive()); assertNull(error.get()); assertEquals(5,load().found(owner.toString())); assertEquals(4,load().income(owner)); assertEquals(11,load().shards(owner.toString()));
    }
    @Test void productionWiringCommitsBeforeNativeLootHasNoMoneyFallbackAndRespectsSilentMode() throws Exception {
        var base=Path.of("src/main/java/ru/cherdakmd/chrdktreasures"); String s=Files.readString(base.resolve("TreasureService.java"));
        assertFalse(s.contains("giveDiamonds")); assertFalse(s.contains("Material.DIAMOND")); assertFalse(s.contains("store.recordTreasure("));
        assertTrue(s.indexOf("plugin.finance().record(player, tier")<s.indexOf("Placement placement = placeInWorld"));
        int admin=s.indexOf("public boolean giveItem"); assertTrue(s.indexOf("plugin.finance().record(player, def.tier()",admin)<s.indexOf("giveStack(player, stack)",admin));
        assertTrue(s.contains("if (!plugin.config().findNotifications() || !store.announce(key(player))) return;")); assertTrue(s.contains("if (plugin.config().findNotifications() && store.announce(key(player)))"));
        String listener=Files.readString(base.resolve("TreasureListener.java")); assertTrue(listener.contains("!plugin.finance().eligible(player)")); assertTrue(listener.contains("if (found == null) return;"));
        String command=Files.readString(base.resolve("TreasureCommandHandler.java")); int gate=command.indexOf("private boolean handleAdmin"); assertTrue(command.indexOf("!sender.hasPermission(\"chrdktreasures.admin\")",gate)<command.indexOf("plugin.finance().status(sender)",gate));
    }
    @Test void ordinaryLootIncomeAndFindAreOneCanonicalTransition() throws Exception {var s=load();s.recordRewardedTreasure(owner,TreasureTier.COMMON,null,now,60000,config(4),21);s=load();assertEquals(1,s.found(owner.toString()));assertEquals(21,s.income(owner));assertTrue(deliver(s,bank()));assertEquals(21,bank().balance(owner));}
    @Test void lootRetryAfterSpendingNeverPaysTheSameCumulativeOutcomeAgain() throws Exception {var s=load();s.recordRewardedTreasure(owner,TreasureTier.COMMON,null,now,60000,config(4),21);var b=bank();deliver(s,b);assertTrue(b.withdraw(owner,21));b.flush();deliver(load(),b);assertEquals(0,b.balance(owner));}
    @Test void lootDebtSurvivesProgressResetAndCap() throws Exception {var s=load();s.recordRewardedTreasure(owner,TreasureTier.COMMON,null,now,60000,config(4),21);s.resetAll();s.flush();s=load();assertEquals(21,s.income(owner));var b=bank();b.setBalance(owner,"Steve",BankStore.MAX_BALANCE);deliver(s,b);assertEquals(21,s.income(owner));b.withdraw(owner,21);deliver(s,b);assertEquals(BankStore.MAX_BALANCE,b.balance(owner));}
    @Test void oldSchemaWithoutLootDoesNotBackpayHistoricalRolls() throws Exception {var s=earned();var r=JsonParser.parseString(Files.readString(s.file())).getAsJsonObject();r.addProperty("version",2);r.remove("loot_totals");Files.writeString(s.file(),r.toString());s=load();assertEquals(4,s.income(owner));s.recordRewardedTreasure(owner,TreasureTier.COMMON,null,now,60000,config(4),9);assertEquals(13,load().income(owner));}
    @Test void schemaDowngradeAndMalformedLootTotalsAreRefused() throws Exception {var s=earned();String original=Files.readString(s.file());var r=JsonParser.parseString(original).getAsJsonObject();r.addProperty("version",2);refuse(r.toString());for(String n:List.of("-1","1.5","\"2\"","9223372036854775808")){r=JsonParser.parseString(original).getAsJsonObject();r.getAsJsonObject("loot_totals").add(owner.toString(),JsonParser.parseString(n));refuse(r.toString());}}
    @Test void onlyOrdinaryCurrencyMaterialsHaveMoneyRates() {assertEquals(1,TreasureMoney.unit("DIAMOND"));assertEquals(9,TreasureMoney.unit("DIAMOND_BLOCK"));assertEquals(0,TreasureMoney.unit("DIAMOND_SWORD"));assertEquals(0,TreasureMoney.unit("DIAMOND_ORE"));}

}

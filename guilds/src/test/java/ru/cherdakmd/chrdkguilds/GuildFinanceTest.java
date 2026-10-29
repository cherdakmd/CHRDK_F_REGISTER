package ru.cherdakmd.chrdkguilds;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Production preview/whole-domain source/WAL, без замены правил казны тестовой реализацией. */
class GuildFinanceTest {
    @TempDir Path dir;
    final UUID leader = UUID.randomUUID(), rival = UUID.randomUUID(), member = UUID.randomUUID();
    final long now = 1_792_000_000_000L;
    final GuildConfig config = GuildConfig.defaults();
    GuildStore store() { return new GuildStore(dir,Logger.getAnonymousLogger()); }
    GuildStore legacy(long money) {
        var store = store(); var one = new Guild("one","Первый","ONE",leader,now); one.deposit(money);
        one.addMember(member,GuildRank.MEMBER); store.add(one);
        var two = new Guild("two","Второй","TWO",rival,now); two.deposit(money); store.add(two);
        store.flush(); return store;
    }
    PaidActionStore source() throws Exception { return new PaidActionStore(dir); }
    GuildStore bind(PaidActionStore source) throws Exception { var s=store(); s.bind(source); return s; }
    GuildService service(GuildStore store) { return new GuildService(config,store); }

    @Test void importOnceKeepsTreasuryMembersAndIgnoresLaterLegacyCorruption() throws Exception {
        legacy(100); var source=source(); var live=bind(source);
        assertEquals(100,live.of(leader).treasury()); assertEquals(leader,live.of(member).leader());
        Files.writeString(dir.resolve("guilds.json"),"{broken");
        var reboot=bind(source()); assertEquals(100,reboot.of(leader).treasury());
        live.flush(); assertEquals("{broken",Files.readString(dir.resolve("guilds.json")));
    }
    @Test void missingImportMarkerCannotOverwriteExistingFinancialDomain() throws Exception {
        var source=source(); source.putValue(GuildStore.FINANCE_KEY,"existing");
        assertThrows(IOException.class,()->bind(source)); assertEquals("existing",source().values().get(GuildStore.FINANCE_KEY));
    }
    @Test void liveServiceRefusesLegacyPurseAndPreviewNeverChangesTreasuryOrSource() throws Exception {
        legacy(100); var source=source(); var live=bind(source); String before=source.snapshot();
        GuildPurse forbidden = (id,amount)-> { throw new AssertionError("native purse invoked"); };
        assertFalse(service(live).deposit(forbidden,leader,20).ok());
        assertFalse(service(live).withdraw(forbidden,leader,20).ok());
        assertFalse(service(live).create(forbidden,UUID.randomUUID(),"Третий","THR",now).ok());
        var preview=GuildFinance.preview(live,config,(s,q)->s.deposit(q,leader,20));
        assertTrue(preview.result().ok()); assertEquals(20,preview.debit()); assertEquals(0,preview.credit());
        assertEquals(100,live.of(leader).treasury()); assertEquals(before,source().snapshot());
        assertEquals(120,live.detached(preview.domain()).of(leader).treasury());
    }
    @Test void depositChargesOnlyRoomAndLeaderWithdrawalQuotesNoPhysicalOutput() throws Exception {
        legacy(Guild.MAX_TREASURY-3); var source=source(); var live=bind(source);
        var deposit=GuildFinance.preview(live,config,(s,q)->s.deposit(q,leader,100));
        assertEquals(3,deposit.debit()); assertEquals(Guild.MAX_TREASURY,live.detached(deposit.domain()).of(leader).treasury());
        var noRight=GuildFinance.preview(live,config,(s,q)->s.withdraw(q,member,20));
        assertFalse(noRight.result().ok()); assertEquals(0,noRight.credit());
        var withdrawal=GuildFinance.preview(live,config,(s,q)->s.withdraw(q,leader,20));
        assertTrue(withdrawal.result().ok()); assertEquals(0,withdrawal.debit()); assertEquals(20,withdrawal.credit());
        source.markRegistered(); source.creditValue(GuildStore.FINANCE_KEY,withdrawal.domain(),leader,20);
        assertEquals(Guild.MAX_TREASURY-23,bind(source()).of(leader).treasury()); assertEquals(20,source().refunds().get(leader));
        assertFalse(source().refunds().containsKey(member));
    }
    @Test void actualBankOnlyChargeRecoversOfflineAndPublishesGuildOnce() throws Exception {
        legacy(100); var source=source(); var live=bind(source); source.markRegistered();
        var bank=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); bank.load();
        assertTrue(bank.registerIncomeSource("guilds",source.lineage())); bank.deposit(leader,"Alex",50); bank.saveNow();
        var preview=GuildFinance.preview(live,config,(s,q)->s.deposit(q,leader,20));
        var journal=new ChargeJournal(dir,"guilds",source.lineage()); String inv=new com.google.gson.Gson().toJson(new String[36]);
        var charge=journal.prepare(leader,20,20,0,0,inv,inv,source.snapshot(),source.previewValue(GuildStore.FINANCE_KEY,preview.domain()));
        assertTrue(bank.settleCharge(charge)); assertEquals(100,live.of(leader).treasury());
        var api=(ChrdkRegisterApi)Proxy.newProxyInstance(ChrdkRegisterApi.class.getClassLoader(),new Class<?>[]{ChrdkRegisterApi.class},(p,m,a)->{
            if(m.getName().equals("bankChargeStatus")) return bank.bankChargeStatus((BankCharge)a[0]);
            throw new AssertionError("offline bank-only path accessed "+m.getName());
        });
        source=source(); journal=new ChargeJournal(dir,"guilds",source.lineage());
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(journal,api,null,source));
        assertEquals(120,bind(source()).of(leader).treasury()); assertEquals(30,bank.balance(leader));
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(journal,api,null,source));
        assertEquals(120,bind(source()).of(leader).treasury()); assertEquals(30,bank.balance(leader));
    }
    @Test void refusedChargeLeavesGuildBeforeImageAndNoIncome() throws Exception {
        legacy(100); var source=source(); var live=bind(source); source.markRegistered();
        var bank=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); bank.load(); bank.registerIncomeSource("guilds",source.lineage());
        var preview=GuildFinance.preview(live,config,(s,q)->s.deposit(q,leader,20));
        var journal=new ChargeJournal(dir,"guilds",source.lineage()); String inv=new com.google.gson.Gson().toJson(new String[36]);
        var charge=journal.prepare(leader,20,20,0,0,inv,inv,source.snapshot(),source.previewValue(GuildStore.FINANCE_KEY,preview.domain()));
        assertFalse(bank.settleCharge(charge)); assertEquals(100,bind(source()).of(leader).treasury());
        assertTrue(source().refunds().isEmpty());
    }
    @Test void warStakeDeclineAndHistoryAreOneDurableCollectiveTransaction() throws Exception {
        legacy(100); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok());
        var reboot=bind(source()); assertEquals(68,reboot.of(leader).treasury()); assertEquals(1,reboot.wars().size());
        assertTrue(service(reboot).declineWar(rival,now+1000).ok()); reboot=bind(source());
        assertEquals(100,reboot.of(leader).treasury()); assertTrue(reboot.wars().isEmpty()); assertEquals(1,reboot.history().size());
        assertFalse(service(reboot).declineWar(rival,now+2000).ok()); assertTrue(source().refunds().isEmpty());
    }
    @Test void fullTreasuryDoesNotLosePendingStakeRefundAcrossRestart() throws Exception {
        legacy(Guild.MAX_TREASURY); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok());
        live.transaction(copy->{copy.of(leader).deposit(32);return true;},false);
        assertTrue(service.declineWar(rival,now+1000).ok());
        var reboot=bind(source()); assertEquals(Guild.MAX_TREASURY,reboot.of(leader).treasury()); assertEquals(32,reboot.of(leader).warReturns());
        var withdrawal=GuildFinance.preview(reboot,config,(s,q)->s.withdraw(q,leader,10));
        source.markRegistered(); source.creditValue(GuildStore.FINANCE_KEY,withdrawal.domain(),leader,withdrawal.credit());
        reboot=bind(source()); assertEquals(Guild.MAX_TREASURY,reboot.of(leader).treasury()); assertEquals(22,reboot.of(leader).warReturns());
        assertEquals(10,source().refunds().get(leader));
    }
    @Test void winnerGetsBothStakesAsCollectiveOverflowNotPersonalIncome() throws Exception {
        legacy(Guild.MAX_TREASURY); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok()); assertTrue(service.acceptWar(rival,now+1000).ok());
        live.transaction(copy->{copy.of(leader).deposit(32);return true;},false);
        assertTrue(service.surrenderWar(rival,now+2000).ok());
        var reboot=bind(source()); assertEquals(64,reboot.of(leader).warReturns()); assertEquals(Guild.MAX_TREASURY,reboot.of(leader).treasury());
        assertTrue(reboot.wars().isEmpty()); assertTrue(source().refunds().isEmpty());
    }
    @Test void frozenSourceRejectsWarMembershipAndPersonalPreviewPublication() throws Exception {
        legacy(100); var source=source(); var live=bind(source); source.bind(()->true);
        assertFalse(service(live).declareWar(leader,"TWO",32,now).ok());
        assertFalse(service(live).leave(member).ok()); assertTrue(live.of(leader).isMember(member)); assertTrue(live.wars().isEmpty());
        assertEquals(100,live.of(leader).treasury());
    }
    @Test void beforeReplaceIoFailureCannotSpendStakeOrAnnounceSuccess() throws Exception {
        legacy(100); var source=source(); var live=bind(source);
        Files.createDirectory(dir.resolve("paid-actions.json.tmp"));
        assertFalse(service(live).declareWar(leader,"TWO",32,now).ok()); assertFalse(source.healthy());
        assertTrue(bind(source()).wars().isEmpty()); assertEquals(100,bind(source()).of(leader).treasury());
    }
    @Test void afterReplaceIoFailureFreezesUntilReloadWithoutReapplyingStake() throws Exception {
        legacy(100); bind(source());
        var ambiguous=new PaidActionStore(dir,()->{throw new IOException("after replace");}); var live=bind(ambiguous);
        assertFalse(service(live).declareWar(leader,"TWO",32,now).ok()); assertFalse(ambiguous.healthy());
        var reboot=bind(source()); assertEquals(68,reboot.of(leader).treasury()); assertEquals(1,reboot.wars().size());
        assertFalse(service(reboot).declareWar(leader,"TWO",32,now).ok()); assertEquals(68,reboot.of(leader).treasury());
    }
    @Test void malformedOrClampedMoneyAndDuplicateOwnersNeverImport() throws Exception {
        var original=legacy(100); String json=original.snapshotJson();
        for (String field:List.of("treasury","war-returns","level","glory","wars-won","wars-lost")) {
            var object=JsonParser.parseString(json).getAsJsonObject(); object.getAsJsonArray("guilds").get(0).getAsJsonObject().addProperty(field,-1);
            assertThrows(IllegalArgumentException.class,()->original.detached(object.toString()),field);
        }
        for (String amount:List.of("100.5","1000001","9223372036854775808")) {
            var object=JsonParser.parseString(json).getAsJsonObject(); object.getAsJsonArray("guilds").get(0).getAsJsonObject().add("treasury",JsonParser.parseString(amount));
            assertThrows(RuntimeException.class,()->original.detached(object.toString()),amount);
        }
        var duplicate=JsonParser.parseString(json).getAsJsonObject(); var roster=duplicate.getAsJsonArray("guilds").get(1).getAsJsonObject().getAsJsonObject("members"); roster.addProperty(member.toString(),"member");
        assertThrows(IllegalArgumentException.class,()->original.detached(duplicate.toString()));
    }
    @Test void unknownWarStatusOrOverlappingReservesCannotBeGuessed() throws Exception {
        legacy(100); var source=source(); var live=bind(source); assertTrue(service(live).declareWar(leader,"TWO",32,now).ok());
        String json=source.values().get(GuildStore.FINANCE_KEY); var object=JsonParser.parseString(json).getAsJsonObject();
        object.getAsJsonArray("wars").get(0).getAsJsonObject().addProperty("status","UNKNOWN");
        String unknown=object.toString(); assertThrows(IllegalArgumentException.class,()->live.detached(unknown));
        object=JsonParser.parseString(json).getAsJsonObject(); var wars=object.getAsJsonArray("wars"); var next=wars.get(0).deepCopy().getAsJsonObject(); next.addProperty("id","second"); wars.add(next);
        String bad=object.toString(); assertThrows(IllegalArgumentException.class,()->live.detached(bad));
        var receipts=JsonParser.parseString(json).getAsJsonObject();
        var kills=new com.google.gson.JsonObject(); kills.add(leader+"|"+rival,com.google.gson.JsonNull.INSTANCE);
        receipts.getAsJsonArray("wars").get(0).getAsJsonObject().add("last-kills",kills);
        assertThrows(IllegalArgumentException.class,()->live.detached(receipts.toString()));
    }
    @Test void creationPreviewQuotesOldPriceAndNeverCreatesBeforeReceipt() throws Exception {
        legacy(100); var source=source(); var live=bind(source); UUID creator=UUID.randomUUID();
        var preview=GuildFinance.preview(live,config,(s,q)->s.create(q,creator,"Третий","THR",now));
        assertTrue(preview.result().ok()); assertEquals(27,preview.debit()); assertEquals(0,preview.credit());
        assertEquals(2,live.count()); assertNull(live.of(creator)); assertNotNull(live.detached(preview.domain()).of(creator));
    }
    @Test void expiredStakeReturnsAtCapOnlyOnceAndHistorySurvivesRestart() throws Exception {
        legacy(Guild.MAX_TREASURY); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok());
        live.transaction(copy->{copy.of(leader).deposit(32);return true;},false);
        long later=now+config.warAcceptSeconds()*1000L+1;
        assertEquals(1,service.tick(later).size());
        var reboot=bind(source()); assertEquals(32,reboot.of(leader).warReturns()); assertEquals(1,reboot.history().size());
        assertTrue(service(reboot).tick(later+1000).isEmpty()); assertEquals(32,reboot.of(leader).warReturns()); assertTrue(source().refunds().isEmpty());
    }
    @Test void activeDrawReturnsBothStakesWithoutCapLossOrLeaderCredit() throws Exception {
        legacy(Guild.MAX_TREASURY); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok()); assertTrue(service.acceptWar(rival,now+1000).ok());
        live.transaction(copy->{copy.of(leader).deposit(32);copy.of(rival).deposit(32);return true;},false);
        assertTrue(service.forceEndWar("ONE",now+2000).ok());
        var reboot=bind(source()); assertEquals(32,reboot.of(leader).warReturns()); assertEquals(32,reboot.of(rival).warReturns());
        assertEquals("DRAW",reboot.history().getFirst().result()); assertTrue(reboot.wars().isEmpty()); assertTrue(source().refunds().isEmpty());
    }
    @Test void disbandPreservesOtherGuildRefundAndNeverPrivatizesCollectiveFunds() throws Exception {
        legacy(Guild.MAX_TREASURY); var source=source(); var live=bind(source); var service=service(live);
        assertTrue(service.declareWar(leader,"TWO",32,now).ok());
        live.transaction(copy->{copy.of(leader).deposit(32);return true;},false);
        assertTrue(service.forceDisband("TWO").ok());
        var reboot=bind(source()); assertEquals(32,reboot.of(leader).warReturns()); assertNull(reboot.of(rival));
        assertTrue(reboot.wars().isEmpty()); assertEquals(1,reboot.history().size()); assertTrue(source().refunds().isEmpty());
    }

}

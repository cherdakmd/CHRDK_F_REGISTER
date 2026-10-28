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

class MilestoneFinanceTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    static final String SOURCE="skills-awards";
    MilestoneStore load() throws Exception { return new MilestoneStore(dir); }
    MilestoneStore initialized() throws Exception { var s=load(); s.initialize(Map.of(owner,Set.of("level:10"))); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    static final class Gateway implements MilestoneAwards.Bank {
        final BankStore bank;
        boolean available=true,known=true;
        Gateway(BankStore bank) { this.bank=bank; }
        public boolean available() { return available&&bank.available(); }
        public boolean registered(String s,UUID l) { return bank.incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return bank.registerIncomeSource(s,l); }
        public boolean known(UUID id) { return known; }
        public long credited(String s,UUID l,UUID id) { return bank.creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return bank.creditEntitlement(s,l,id.toString(),id,total); }
    }
    @Test void legacyClaimsImportOnceWithoutPayingOldPhysicalRewardsAgain() throws Exception {
        var s=initialized(); assertTrue(s.claimed(owner).contains("level:10")); assertTrue(s.totals().isEmpty());
        s.reserve(owner,"level:10",99); assertTrue(load().totals().isEmpty());
        s.initialize(Map.of(owner,Set.of("level:25"))); assertFalse(load().claimed(owner).contains("level:25"));
    }
    @Test void newClaimAndObligationFreezeTogetherBeforeBankAndLegacyMarker() throws Exception {
        var s=initialized(); assertTrue(s.reserve(owner,"level:25",17));
        s=load(); assertTrue(s.claimed(owner).contains("level:25")); assertEquals(17,s.totals().get(owner));
        assertTrue(s.reserve(owner,"level:25",99)); assertEquals(17,load().totals().get(owner));
        s.reserve(owner,"level:50",3); assertEquals(20,load().totals().get(owner));
    }
    @Test void realBankDeltaIsIdempotentAfterRestartSpendingAndFutureMilestone() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank();
        assertTrue(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17));
        assertEquals(17,b.balance(owner)); b.withdraw(owner,10); b.saveNow(); s=load(); b=bank();
        assertTrue(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); assertEquals(7,b.balance(owner));
        s.reserve(owner,"level:50",3); assertTrue(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,20)); assertEquals(10,b.balance(owner));
    }
    @Test void fullAndPartialBankCapPreserveRemainderAcrossBothRestarts() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank(); b.deposit(owner,"Alex",BankStore.MAX_BALANCE); b.saveNow();
        assertFalse(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); assertEquals(17,load().totals().get(owner));
        b.withdraw(owner,5); assertFalse(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); b=bank();
        assertEquals(5,b.creditedEntitlement(SOURCE,s.lineage(),owner.toString(),owner));
        b.withdraw(owner,12); assertTrue(MilestoneAwards.deliver(load(),new Gateway(b),SOURCE,owner,17));
        assertTrue(MilestoneAwards.deliver(load(),new Gateway(bank()),SOURCE,owner,17)); assertEquals(BankStore.MAX_BALANCE,bank().balance(owner));
    }
    @Test void bankUnavailableDoesNotPreventDurableQualificationOrInventMoney() throws Exception {
        var s=initialized(); var b=bank(); var gateway=new Gateway(b); gateway.available=false;
        s.reserve(owner,"level:25",17); assertFalse(MilestoneAwards.deliver(s,gateway,SOURCE,owner,17));
        assertEquals(0,b.balance(owner)); assertFalse(s.registered()); assertEquals(17,load().totals().get(owner));
        gateway.available=true; assertTrue(MilestoneAwards.deliver(s,gateway,SOURCE,owner,17));
    }
    @Test void unknownUuidRetainsIncomeWithoutCreatingBankAccountUntilKnown() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank(); var gateway=new Gateway(b); gateway.known=false;
        assertFalse(MilestoneAwards.deliver(s,gateway,SOURCE,owner,17)); assertFalse(b.knows(owner)); assertEquals(17,load().totals().get(owner));
        gateway.known=true; assertTrue(MilestoneAwards.deliver(s,gateway,SOURCE,owner,17)); assertEquals(17,b.balance(owner));
    }
    @Test void otherNamespaceCannotStealRegisteredLineageAndWrongGenerationDoesNotMint() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank(); b.registerIncomeSource(SOURCE,UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); assertEquals(0,b.balance(owner)); assertFalse(s.registered());
    }
    @Test void unbackedTotalIsRefusedEvenWhenApiCallerRequestsMore() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank();
        assertThrows(IllegalArgumentException.class,()->MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,18)); assertEquals(0,b.balance(owner));
    }
    @Test void olderSourceThanBankReceiptIsConflictNotNegativeDeltaOrReset() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank();
        assertTrue(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17));
        b.creditEntitlement(SOURCE,s.lineage(),owner.toString(),owner,20);
        assertThrows(IllegalStateException.class,()->MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); assertEquals(20,b.balance(owner));
    }
    @Test void beforeReplaceFailureCannotCreateClaimWithoutObligation() throws Exception {
        var s=initialized(); Files.createDirectory(dir.resolve("milestone-awards.json.tmp"));
        assertThrows(IOException.class,()->s.reserve(owner,"level:25",17)); assertFalse(s.healthy());
        assertFalse(load().claimed(owner).contains("level:25")); assertTrue(load().totals().isEmpty());
    }
    @Test void afterReplaceFailureQuarantinesAndReloadFindsClaimAndAmountTogether() throws Exception {
        initialized(); var s=new MilestoneStore(dir,()->{throw new IOException("after replace");});
        assertThrows(IOException.class,()->s.reserve(owner,"level:25",17)); assertFalse(s.healthy());
        assertTrue(load().claimed(owner).contains("level:25")); assertEquals(17,load().totals().get(owner));
        assertThrows(IllegalStateException.class,()->s.reserve(owner,"level:50",3));
    }
    @Test void failedBankCreditKeepsSourceAndCanBeRetriedAfterBankRepair() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var b=bank();
        b.registerIncomeSource(SOURCE,s.lineage()); s.markRegistered(); Files.createDirectory(dir.resolve("core/bank.json.tmp"));
        assertFalse(MilestoneAwards.deliver(s,new Gateway(b),SOURCE,owner,17)); assertEquals(17,load().totals().get(owner));
        Files.delete(dir.resolve("core/bank.json.tmp")); assertTrue(MilestoneAwards.deliver(load(),new Gateway(bank()),SOURCE,owner,17)); assertEquals(17,bank().balance(owner));
    }
    @Test void nullMissingFractionalAndInvalidOwnerMetadataNeverResetSource() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var file=dir.resolve("milestone-awards.json"); String good=Files.readString(file);
        for(String key:List.of("schema","lineage","imported","registered","awards")) {
            var json=JsonParser.parseString(good).getAsJsonObject(); json.remove(key); String bad=json.toString(); Files.writeString(file,bad);
            assertThrows(IOException.class,this::load); assertEquals(bad,Files.readString(file));
        }
        var json=JsonParser.parseString(good).getAsJsonObject(); json.getAsJsonObject("awards").getAsJsonObject(owner.toString()).addProperty("level:25",1.5);
        Files.writeString(file,json.toString()); assertThrows(IOException.class,this::load);
        json=JsonParser.parseString(good).getAsJsonObject(); json.getAsJsonObject("awards").add("0-0-0-0-1",new JsonObject()); Files.writeString(file,json.toString()); assertThrows(IOException.class,this::load);
    }
    @Test void invalidAwardAmountsAndKeysCannotChangeExistingAwards() throws Exception {
        var s=initialized();
        assertThrows(IllegalArgumentException.class,()->s.reserve(owner,"level:25",-1));
        assertThrows(IllegalArgumentException.class,()->s.reserve(owner,"level:25",1_000_001));
        assertThrows(IllegalArgumentException.class,()->s.reserve(null,"level:25",1));
        assertThrows(IllegalArgumentException.class,()->s.reserve(owner,"bad key",1)); assertTrue(load().totals().isEmpty());
    }
    @Test void zeroPriceMilestoneIsStillOnceAndDoesNotGainMoneyAfterConfigReload() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",0); s=load(); s.reserve(owner,"level:25",50); assertTrue(load().totals().isEmpty());
        assertTrue(load().claimed(owner).contains("level:25"));
    }
    @Test void multipleCrossedLevelsShareOneAtomicReservationAndKeepFrozenPrices() throws Exception {
        var s=initialized(); s.reserveAll(owner,Map.of("level:25",17L,"level:50",3L));
        s=load(); assertEquals(20,s.totals().get(owner)); assertTrue(s.claimed(owner).containsAll(Set.of("level:25","level:50")));
        s.reserveAll(owner,Map.of("level:25",99L,"level:50",99L)); assertEquals(17,s.amount(owner,"level:25")); assertEquals(3,s.amount(owner,"level:50"));
    }
    @Test void invalidOrFailedBatchDoesNotLeaveFirstCrossedLevelPaidAlone() throws Exception {
        var s=initialized(); assertThrows(IllegalArgumentException.class,()->s.reserveAll(owner,Map.of("level:25",17L,"level:50",-1L)));
        assertTrue(load().totals().isEmpty());
        Files.createDirectory(dir.resolve("milestone-awards.json.tmp"));
        assertThrows(IOException.class,()->s.reserveAll(owner,Map.of("level:25",17L,"level:50",3L)));
        assertFalse(load().claimed(owner).contains("level:25")); assertFalse(load().claimed(owner).contains("level:50"));
    }

    @Test void bankAfterReplaceFailureRecoversReceiptWithoutPayingAgain() throws Exception {
        var s=initialized(); s.reserve(owner,"level:25",17); var bank=bank();
        bank.registerIncomeSource(SOURCE,s.lineage()); s.markRegistered();
        var ambiguous=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger(),()->{throw new IOException("after bank replace");}); ambiguous.load();
        assertFalse(MilestoneAwards.deliver(s,new Gateway(ambiguous),SOURCE,owner,17)); assertFalse(ambiguous.available());
        var reboot=bank(); assertEquals(17,reboot.balance(owner));
        assertTrue(MilestoneAwards.deliver(load(),new Gateway(reboot),SOURCE,owner,17)); assertEquals(17,bank().balance(owner));
    }

}

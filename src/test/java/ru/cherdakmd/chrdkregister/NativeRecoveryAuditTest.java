package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Production executor + настоящие BankStore/ChargeJournal/PaidActionStore. Fake только native playerdata. */
class NativeRecoveryAuditTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(); final Logger log=Logger.getAnonymousLogger();
    PaidActionStore source() throws Exception { return new PaidActionStore(dir.resolve("module")); }
    ChargeJournal journal() throws Exception { return new ChargeJournal(dir.resolve("module"),"guilds",source().lineage()); }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),log); b.load(); return b; }
    BankCharge prepare(boolean cash) throws Exception {
        var s=source(); s.markRegistered(); var b=bank(); b.registerIncomeSource("guilds",s.lineage()); b.open(owner,"Alex"); if(!cash) b.deposit(owner,"Alex",5); b.flush();
        return journal().prepare(owner,5,cash?0:5,cash?9:0,cash?4:0,"before",cash?"after":"before",s.snapshot(),s.previewValue("fixture","new domain"));
    }
    ChargeRecovery.Decision recover(BankCharge.Status status,InventoryTransfer.Inventory bag) throws Exception { return ChargeRecovery.recover(journal(),status,bag,source()); }
    @Test void guildsPermissionIsNotMissingAndUnknownSourcesRemainDenied() {
        assertEquals("chrdkguilds.use",BankCharge.permission("guilds")); assertEquals("chrdklottery.use",BankCharge.permission("lottery"));
        assertEquals("chrdkgoals.use",BankCharge.permission("common-goals")); assertEquals("chrdktele.use",BankCharge.permission("teleports")); assertEquals("chrdkclaims.teleport",BankCharge.permission("claim-teleports")); assertNull(BankCharge.permission("other")); assertNull(BankCharge.permission(null));
    }
    @Test void committedCashPublishesOnlyAfterDurableNativeProof() throws Exception {
        var c=prepare(true); assertTrue(bank().settleCharge(c)); var bag=new InventoryTransferTest.Bag(owner);
        assertEquals(ChargeRecovery.Decision.APPLY_AFTER,recover(bank().bankChargeStatus(c),bag)); assertNull(journal().pending()); assertEquals("after",bag.durableState); assertEquals(c.transaction().toString(),bag.durableToken); assertEquals(4,bank().balance(owner));
    }
    @Test void committedAfterSnapshotWithoutWitnessKeepsJournalAndSourceUnpublished() throws Exception {
        var c=prepare(true); bank().settleCharge(c); var bag=new InventoryTransferTest.Bag(owner); bag.state="after"; String before=source().snapshot();
        assertEquals(ChargeRecovery.Decision.CONFLICT,recover(bank().bankChargeStatus(c),bag)); assertNotNull(journal().pending()); assertEquals(before,source().snapshot()); assertEquals(0,bag.applies);
    }
    @Test void failingCheckpointCannotCloseModuleEvenWithInMemoryWitness() throws Exception {
        var c=prepare(true); bank().settleCharge(c); var bag=new InventoryTransferTest.Bag(owner); bag.failSave=true;
        assertThrows(IOException.class,()->recover(bank().bankChargeStatus(c),bag)); assertThrows(IOException.class,()->recover(bank().bankChargeStatus(c),bag)); assertNotNull(journal().pending()); assertNull(bag.durableToken);
        bag.failSave=false; assertEquals(ChargeRecovery.Decision.COMPLETE,recover(bank().bankChargeStatus(c),bag)); assertEquals(1,bag.applies); assertNull(journal().pending());
    }
    @Test void bankUnavailableOrUnknownReceiptNeverTouchesPlayerdata() throws Exception {
        prepare(true); var bag=new InventoryTransferTest.Bag(owner);
        assertEquals(ChargeRecovery.Decision.WAIT,recover(BankCharge.Status.UNAVAILABLE,bag)); assertEquals(ChargeRecovery.Decision.CONFLICT,recover(BankCharge.Status.CONFLICT,bag)); assertEquals(0,bag.applies); assertEquals(0,bag.checkpoints); assertNotNull(journal().pending());
    }
    @Test void unknownPayerWaitsAndNeverAppliesAnotherPlayersStorage() throws Exception {
        var c=prepare(true); bank().settleCharge(c); var bag=new InventoryTransferTest.Bag(UUID.randomUUID());
        assertEquals(ChargeRecovery.Decision.WAIT,recover(BankCharge.Status.COMMITTED,bag)); assertEquals(0,bag.applies); assertNotNull(journal().pending());
    }
    @Test void bankOnlyRecoveryIgnoresOfflineAndChangedInventory() throws Exception {
        var c=prepare(false); bank().settleCharge(c); assertEquals(ChargeRecovery.Decision.COMPLETE,recover(bank().bankChargeStatus(c),null)); assertNull(journal().pending()); assertEquals(0,bank().balance(owner));
    }
    @Test void missingReceiptOnlyRollsBackUntouchedSourceWithoutWitness() throws Exception {
        var c=prepare(true); var bag=new InventoryTransferTest.Bag(owner); bag.token=c.transaction().toString();
        assertEquals(ChargeRecovery.Decision.CONFLICT,recover(BankCharge.Status.MISSING,bag)); assertNotNull(journal().pending()); bag.token=null;
        assertEquals(ChargeRecovery.Decision.ROLLBACK,recover(BankCharge.Status.MISSING,bag)); assertNull(journal().pending()); assertEquals(0,bag.applies);
    }
    @Test void publishedSourceNeedsWitnessNotJustAfterSnapshot() throws Exception {
        var c=prepare(true); bank().settleCharge(c); var p=journal().pending(); source().publish(p.beforeState(),p.afterState()); var bag=new InventoryTransferTest.Bag(owner); bag.state="after";
        assertEquals(ChargeRecovery.Decision.CONFLICT,recover(BankCharge.Status.COMMITTED,bag)); bag.token=c.transaction().toString(); bag.state="legitimate later state";
        assertEquals(ChargeRecovery.Decision.COMPLETE,recover(BankCharge.Status.COMMITTED,bag)); assertEquals("legitimate later state",bag.durableState); assertEquals(0,bag.applies);
    }
    @Test void sourceAfterRenameFailureRecoversNonceWithoutSecondCashConsumption() throws Exception {
        var c=prepare(true); bank().settleCharge(c); var bag=new InventoryTransferTest.Bag(owner); var failed=new PaidActionStore(dir.resolve("module"),()->{throw new IOException("source after replace");});
        assertThrows(IOException.class,()->ChargeRecovery.recover(journal(),BankCharge.Status.COMMITTED,bag,failed)); assertFalse(failed.healthy()); assertNotNull(journal().pending());
        bag.state="later current inventory"; assertEquals(ChargeRecovery.Decision.COMPLETE,recover(BankCharge.Status.COMMITTED,bag)); assertEquals(1,bag.applies); assertEquals("later current inventory",bag.durableState);
    }
    @Test void runtimeBankFailureAfterRenameQuarantinesInsteadOfReportingMissingPayment() throws Exception {
        var c=prepare(true); var b=new BankStore(dir.resolve("core"),log,()->{throw new IllegalStateException("bank after replace");}); b.load();
        assertFalse(b.settleCharge(c)); assertFalse(b.available()); assertEquals(BankCharge.Status.UNAVAILABLE,b.bankChargeStatus(c)); b.flush(); assertEquals(BankCharge.Status.COMMITTED,bank().bankChargeStatus(c)); assertEquals(4,bank().balance(owner));
    }
    @Test void runtimeJournalFailureAfterRenameCannotBeOverwrittenFromOldMemory() throws Exception {
        prepare(true); var j=new ChargeJournal(dir.resolve("module"),"guilds",source().lineage(),()->{throw new IllegalStateException("journal after replace");}); var c=j.pending().payment();
        assertThrows(IllegalStateException.class,()->j.complete(c)); assertFalse(j.healthy()); assertNull(journal().pending()); assertThrows(IllegalStateException.class,()->j.rollback(c));
    }
    @Test void runtimeSourceFailureAfterRenameAlsoQuarantines() throws Exception {
        prepare(true); var p=journal().pending(); var s=new PaidActionStore(dir.resolve("module"),()->{throw new IllegalStateException("source after replace");});
        assertThrows(IllegalStateException.class,()->s.publish(p.beforeState(),p.afterState())); assertFalse(s.healthy()); assertEquals(p.afterState(),source().snapshot());
    }
    @Test void productionBoundaryChecksNativePlanAndDoesNotReserveAuctionKeysForBankGuard() throws Exception {
        String nativeCode=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java"));
        assertTrue(nativeCode.indexOf("!valid(pending)")<nativeCode.indexOf("ChargeRecovery.recover(journal")); assertTrue(nativeCode.contains("public void checkpoint() { player.saveData(); }")); assertTrue(nativeCode.indexOf("setStorageContents(decode(after))")<nativeCode.indexOf("checkpoint();"));
        String api=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java")); assertTrue(api.contains("BankCharge.permission(payment.source())")); assertTrue(api.contains("!payer.hasPermission(permission)"));
    }
}

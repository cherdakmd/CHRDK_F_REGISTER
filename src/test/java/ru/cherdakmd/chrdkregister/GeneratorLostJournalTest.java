package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorLostJournalTest {
    @TempDir Path dir;
    final String source = "generator-upgrades";
    final UUID payer = UUID.randomUUID();
    final Logger log = Logger.getAnonymousLogger();

    ChrdkRegisterApi api(BankStore b) {
        return (ChrdkRegisterApi) Proxy.newProxyInstance(ChrdkRegisterApi.class.getClassLoader(), new Class<?>[]{ChrdkRegisterApi.class}, (p,m,a) -> switch (m.getName()) {
            case "personalBankAvailable" -> true;
            case "incomeSourceAvailable" -> b.incomeSourceAvailable((String)a[0], (UUID)a[1]);
            case "bankChargeStatus" -> b.bankChargeStatus((BankCharge)a[0]);
            case "bankChargeSequence" -> b.bankChargeSequence((String)a[0], (UUID)a[1]);
            default -> throw new AssertionError(m.getName());
        });
    }
    @Test void lostJournalRebasesOnlyAfterExplicitAcknowledgementAndPreservesEvidence() throws Exception {
        var s = new PaidActionStore(dir.resolve("generator")); s.markRegistered();
        var b = new BankStore(dir.resolve("core"), log); b.load(); b.registerIncomeSource(source,s.lineage()); b.deposit(payer,"test",100);
        var old = new ChargeJournal(dir.resolve("old"),source,s.lineage());
        for (int i=0;i<5;i++) {
            var c=old.prepare(payer,1,1,0,0,"inventory","inventory","before","after");
            assertTrue(b.settleCharge(c)); old.complete(c);
        }
        var j = new ChargeJournal(dir.resolve("generator"),source,s.lineage());
        var a = new PaidActionStore.Action(UUID.randomUUID(),payer,payer,5,"generator-tier","tier:2",PaidActionStore.Phase.READY);
        var pending=j.prepare(payer,5,5,0,0,"inventory","inventory",s.snapshot(),s.preview(a));
        long balance=b.balance(payer); String evidence=Files.readString(dir.resolve("generator/bank-charge.json"));
        assertEquals(BankCharge.Status.CONFLICT,b.bankChargeStatus(pending));
        assertFalse(PaidActions.rebaseLostGeneratorHistory(source,s,j,api(b),UUID.randomUUID(),"Console",log));
        assertFalse(PaidActions.rebaseLostGeneratorHistory("teleports",s,j,api(b),pending.transaction(),"Console",log));
        assertTrue(PaidActions.rebaseLostGeneratorHistory(source,s,j,api(b),pending.transaction(),"Console",log));
        assertEquals(balance,b.balance(payer)); assertTrue(s.actions().isEmpty()); assertTrue(s.refunds().isEmpty());
        assertNull(j.pending()); assertEquals(6,j.nextSequence());
        assertEquals(evidence,Files.readString(dir.resolve("generator/bank-charge-lost-"+pending.transaction()+".json")));
        var restarted=new ChargeJournal(dir.resolve("generator"),source,s.lineage()); assertEquals(6,restarted.nextSequence());
        assertFalse(PaidActions.rebaseLostGeneratorHistory(source,s,restarted,api(b),pending.transaction(),"Console",log));
        var next=restarted.prepare(payer,5,5,0,0,"inventory","inventory",s.snapshot(),s.preview(a));
        assertEquals(BankCharge.Status.MISSING,b.bankChargeStatus(next)); assertTrue(b.settleCharge(next));
    }
    @Test void journalCannotRebaseWithoutAConflictAndAnEmptySource() throws Exception {
        var s=new PaidActionStore(dir); s.markRegistered(); var b=new BankStore(dir.resolve("core"),log); b.load(); b.registerIncomeSource(source,s.lineage());
        var j=new ChargeJournal(dir,source,s.lineage()); var a=new PaidActionStore.Action(UUID.randomUUID(),payer,payer,5,"generator-tier","tier:2",PaidActionStore.Phase.READY);
        var c=j.prepare(payer,5,5,0,0,"inventory","inventory",s.snapshot(),s.preview(a));
        assertFalse(PaidActions.rebaseLostGeneratorHistory(source,s,j,api(b),c.transaction(),"Console",log));
        assertNotNull(j.pending()); assertEquals(1,j.nextSequence());
        s.publish(s.snapshot(),j.pending().afterState());
        assertFalse(PaidActions.rebaseLostGeneratorHistory(source,s,j,api(b),c.transaction(),"Console",log));
        assertNotNull(j.pending());
    }
    @Test void archiveWriteFailureDoesNotClearPending() throws Exception {
        var s=new PaidActionStore(dir); s.markRegistered(); var j=new ChargeJournal(dir,source,s.lineage());
        var a=new PaidActionStore.Action(UUID.randomUUID(),payer,payer,5,"generator-tier","tier:2",PaidActionStore.Phase.READY);
        var c=j.prepare(payer,5,5,0,0,"inventory","inventory",s.snapshot(),s.preview(a));
        Files.createDirectory(dir.resolve("bank-charge-lost-"+c.transaction()+".json"));
        assertThrows(Exception.class,()->j.archiveAndRebase(c,5));
        assertFalse(j.healthy()); assertNotNull(new ChargeJournal(dir,source,s.lineage()).pending());
    }
}

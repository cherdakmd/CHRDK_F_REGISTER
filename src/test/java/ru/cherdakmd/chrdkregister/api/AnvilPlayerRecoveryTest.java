package ru.cherdakmd.chrdkregister.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnvilPlayerRecoveryTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(),op=UUID.randomUUID();
    final AnvilEscrowSnapshot.Experience before=new AnvilEscrowSnapshot.Experience(12,123,.5f), after=new AnvilEscrowSnapshot.Experience(9,123,.5f);
    byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
    AnvilEscrowSnapshot snapshot(){return new AnvilEscrowSnapshot(op,owner,3,before,after,b("left"),b("book"),b("result"),b("rest"),b("before"),b("after"));}
    AnvilTransactionJournal journal() throws IOException { var j=new AnvilTransactionJournal(dir,owner);j.prepareSnapshot(snapshot());return j; }
    final class State implements AnvilPlayerRecovery.State {
        String witness=AnvilPlayerRecovery.beforeWitness(op), inventory="before"; AnvilEscrowSnapshot.Experience xp=before;
        boolean online=true,valid=true,failApply,failSave;int applies,checkpoints;
        public UUID owner(){return owner;} public boolean available(){return online;} public String witness(){return witness;}
        public boolean valid(AnvilEscrowSnapshot s){return valid;}
        public boolean matches(byte[] data,AnvilEscrowSnapshot.Experience e){return inventory.equals(new String(data,StandardCharsets.UTF_8))&&xp.equals(e);}
        public void applyAndSave(byte[] data,AnvilEscrowSnapshot.Experience e,String token)throws IOException {
            applies++;inventory=new String(data,StandardCharsets.UTF_8);xp=e;witness=token;
            if(failApply)throw new IOException("unknown save");
        }
        public void checkpoint()throws IOException {checkpoints++;if(failSave)throw new IOException("unknown checkpoint");}
    }
    @Test void beforeIsAppliedOnceAndAfterOnlyCheckpoints()throws Exception {
        var j=journal();var s=new State();assertEquals(AnvilPlayerRecovery.Outcome.APPLIED,AnvilPlayerRecovery.recover(j,s));
        assertEquals("after",s.inventory);assertEquals(after,s.xp);assertEquals(1,s.applies);
        assertEquals(AnvilPlayerRecovery.Outcome.APPLIED,AnvilPlayerRecovery.recover(j,s));assertEquals(1,s.applies);assertEquals(2,s.checkpoints);
        assertNotNull(j.pending()); // coordinator, not the adapter, must finalize
    }
    @Test void uncertainSaveIsNotRolledBackOrReapplied()throws Exception {
        var j=journal();var s=new State();s.failApply=true;assertThrows(IOException.class,()->AnvilPlayerRecovery.recover(j,s));
        assertEquals(1,s.applies);s.failApply=false;
        assertEquals(AnvilPlayerRecovery.Outcome.APPLIED,AnvilPlayerRecovery.recover(j,s));assertEquals(1,s.applies);
    }
    @Test void conflictsAndMissingProofNeverTouchItemsOrXp()throws Exception {
        var j=journal();var s=new State();s.witness=null;
        assertEquals(AnvilPlayerRecovery.Outcome.UNRESOLVED,AnvilPlayerRecovery.recover(j,s));
        s.witness=AnvilPlayerRecovery.beforeWitness(op);s.inventory="changed";
        assertEquals(AnvilPlayerRecovery.Outcome.UNRESOLVED,AnvilPlayerRecovery.recover(j,s));
        s.inventory="before";s.valid=false;assertEquals(AnvilPlayerRecovery.Outcome.UNRESOLVED,AnvilPlayerRecovery.recover(j,s));
        s.online=false;assertEquals(AnvilPlayerRecovery.Outcome.WAIT,AnvilPlayerRecovery.recover(j,s));assertEquals(0,s.applies);
    }
    @Test void failedCheckpointRetainsPendingIntent()throws Exception {
        var j=journal();var s=new State();s.witness=AnvilPlayerRecovery.afterWitness(op);s.inventory="after";s.xp=after;s.failSave=true;
        assertThrows(IOException.class,()->AnvilPlayerRecovery.recover(j,s));assertNotNull(j.pending());assertEquals(0,s.applies);
    }
    @Test void destinationRequiresExactlyTwoFreeSlotsAndNoOtherChanges() {
        String[] before=new String[36],after=new String[36];before[5]="existing";after[5]="existing";after[1]="result";after[3]="rest";
        assertTrue(AnvilPlayerRecovery.destinations(before,after,"result","rest"));
        after[5]="tampered";assertFalse(AnvilPlayerRecovery.destinations(before,after,"result","rest"));
        after[5]="existing";after[4]="extra";assertFalse(AnvilPlayerRecovery.destinations(before,after,"result","rest"));
        after[4]=null;before[1]="occupied";assertFalse(AnvilPlayerRecovery.destinations(before,after,"result","rest"));
        before[1]=null;after[3]="result";assertTrue(AnvilPlayerRecovery.destinations(before,after,"result","result"));
    }
    @Test void levelTransitionRequiresExactXpState() {
        assertTrue(AnvilPlayerRecovery.levelTransition(before,after,3));
        assertFalse(AnvilPlayerRecovery.levelTransition(before,after,4));
        assertFalse(AnvilPlayerRecovery.levelTransition(before,new AnvilEscrowSnapshot.Experience(9,124,.5f),3));
        assertFalse(AnvilPlayerRecovery.levelTransition(before,new AnvilEscrowSnapshot.Experience(9,123,.6f),3));
    }
}

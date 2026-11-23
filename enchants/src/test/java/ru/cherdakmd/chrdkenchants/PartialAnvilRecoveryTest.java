package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PartialAnvilRecoveryTest {
    private final UUID owner=UUID.randomUUID(), op=UUID.randomUUID();
    private final PartialAnvilRecovery.Intent intent=new PartialAnvilRecovery.Intent(op,owner,"storage+xp:before","storage+xp:after");
    private static final class State implements PartialAnvilRecovery.PlayerState {
        final UUID owner; boolean available=true, failApply, failCheckpoint; int applications, saves;
        String witness, snapshot;
        State(UUID owner,String witness,String snapshot) { this.owner=owner;this.witness=witness;this.snapshot=snapshot; }
        public UUID owner(){return owner;}
        public boolean available(){return available;}
        public String witness(){return witness;}
        public boolean matches(String value){return snapshot.equals(value);}
        public void applyAndSave(String value,String token) throws IOException {
            applications++;snapshot=value;witness=token;
            if(failApply)throw new IOException("unknown save outcome");
        }
        public void checkpoint() throws IOException { saves++;if(failCheckpoint)throw new IOException("unknown checkpoint outcome"); }
    }
    @Test void beforeWitnessCommitsAllFieldsOnceAndAfterWitnessOnlyCheckpoints() throws Exception {
        var s=new State(owner,intent.beforeWitness(),intent.before());
        assertEquals(PartialAnvilRecovery.Outcome.APPLIED,PartialAnvilRecovery.recover(intent,s));
        assertEquals(intent.after(),s.snapshot);assertEquals(intent.afterWitness(),s.witness);assertEquals(1,s.applications);
        assertEquals(PartialAnvilRecovery.Outcome.APPLIED,PartialAnvilRecovery.recover(intent,s));
        assertEquals(1,s.applications);assertEquals(2,s.saves);
    }
    @Test void ambiguousStatesNeverIssueResultOrDebitAgain() throws Exception {
        for(var s: new State[]{new State(owner,null,intent.before()),
                new State(owner,UUID.randomUUID()+":before",intent.before()),
                new State(owner,intent.beforeWitness(),intent.after()),
                new State(owner,intent.afterWitness(),intent.before()),
                new State(owner,intent.beforeWitness(),"changed")}) {
            assertEquals(PartialAnvilRecovery.Outcome.UNRESOLVED,PartialAnvilRecovery.recover(intent,s));
            assertEquals(0,s.applications);assertEquals(0,s.saves);
        }
    }
    @Test void ioFailureAfterApplyNeverRollsBackOrReapplies() throws Exception {
        var s=new State(owner,intent.beforeWitness(),intent.before());s.failApply=true;
        assertThrows(IOException.class,()->PartialAnvilRecovery.recover(intent,s));
        assertEquals(intent.afterWitness(),s.witness);assertEquals(intent.after(),s.snapshot);
        s.failApply=false;
        assertEquals(PartialAnvilRecovery.Outcome.APPLIED,PartialAnvilRecovery.recover(intent,s));
        assertEquals(1,s.applications);
    }
    @Test void failedCheckpointRemainsPendingUntilSuccessfulCheckpoint() throws Exception {
        var s=new State(owner,intent.afterWitness(),intent.after());s.failCheckpoint=true;
        assertThrows(IOException.class,()->PartialAnvilRecovery.recover(intent,s));
        assertEquals(0,s.applications);s.failCheckpoint=false;
        assertEquals(PartialAnvilRecovery.Outcome.APPLIED,PartialAnvilRecovery.recover(intent,s));
        assertEquals(0,s.applications);
    }
    @Test void offlineOrWrongOwnerWaitsWithoutAccessingTransaction() throws Exception {
        var wrong=new State(UUID.randomUUID(),intent.beforeWitness(),intent.before());
        assertEquals(PartialAnvilRecovery.Outcome.WAIT,PartialAnvilRecovery.recover(intent,wrong));
        var offline=new State(owner,intent.beforeWitness(),intent.before());offline.available=false;
        assertEquals(PartialAnvilRecovery.Outcome.WAIT,PartialAnvilRecovery.recover(intent,offline));
        assertEquals(PartialAnvilRecovery.Outcome.WAIT,PartialAnvilRecovery.recover(intent,null));
        assertEquals(0,wrong.applications);assertEquals(0,offline.applications);
    }
    @Test void invalidIntentsCannotRepresentAnOperation() {
        assertThrows(IllegalArgumentException.class,()->new PartialAnvilRecovery.Intent(op,owner,"same","same"));
        assertThrows(IllegalArgumentException.class,()->new PartialAnvilRecovery.Intent(null,owner,"x","y"));
        assertThrows(IllegalArgumentException.class,()->new PartialAnvilRecovery.Intent(op,owner,"","x"));
        assertThrows(IllegalArgumentException.class,()->new PartialAnvilRecovery.Intent(op,owner,"x".repeat(4*1024*1024+1),"y"));
    }
}

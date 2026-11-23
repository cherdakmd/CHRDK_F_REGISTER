package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AnvilEscrowSnapshot;
import ru.cherdakmd.chrdkregister.api.AnvilTransactionJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnvilEscrowSnapshotTest {
    @TempDir Path dir;
    final UUID op=UUID.randomUUID(), owner=UUID.randomUUID();
    byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    AnvilEscrowSnapshot snapshot(byte[] left) {
        return new AnvilEscrowSnapshot(op,owner,4,new AnvilEscrowSnapshot.Experience(10,100,.5f),
                new AnvilEscrowSnapshot.Experience(6,100,.5f),left,bytes("book+pdc"),bytes("result+pdc"),
                bytes("remainder+pdc"),bytes("state-before"),bytes("state-after"));
    }
    @Test void journalRoundTripPreservesAllFieldsAndOriginalItemBytes() throws Exception {
        var s=snapshot(bytes("left+pdc"));var j=new AnvilTransactionJournal(dir,owner);j.prepareSnapshot(s);
        var restored=new AnvilTransactionJournal(dir,owner).pendingSnapshot();
        assertEquals(op,restored.operation());assertEquals(owner,restored.owner());assertEquals(4,restored.levelCost());
        assertEquals(s.beforeXp(),restored.beforeXp());assertEquals(s.afterXp(),restored.afterXp());
        assertArrayEquals(s.left(),restored.left());assertArrayEquals(s.book(),restored.book());
        assertArrayEquals(s.result(),restored.result());assertArrayEquals(s.remainder(),restored.remainder());
        assertArrayEquals(s.beforeState(),restored.beforeState());assertArrayEquals(s.afterState(),restored.afterState());
    }
    @Test void arraysCannotBeMutatedThroughCallerOrGetters() {
        byte[] left=bytes("left");var s=snapshot(left);left[0]=0;
        var exposed=s.left();exposed[0]=0;assertArrayEquals(bytes("left"),s.left());
    }
    @Test void damagedTruncatedOrOversizeSnapshotsFailClosed() {
        var s=snapshot(bytes("left"));byte[] encoded=Base64.getDecoder().decode(s.encode());encoded[10]^=1;
        assertThrows(IllegalArgumentException.class,()->AnvilEscrowSnapshot.decode(Base64.getEncoder().encodeToString(encoded)));
        assertThrows(IllegalArgumentException.class,()->AnvilEscrowSnapshot.decode("AA=="));
        assertThrows(IllegalArgumentException.class,()->AnvilEscrowSnapshot.decode("x".repeat(4*1024*1024+1)));
        assertThrows(IllegalArgumentException.class,()->snapshot(new byte[256*1024+1]));
    }
    @Test void envelopeIdentityMismatchQuarantinesJournal() throws Exception {
        var s=snapshot(bytes("left"));var j=new AnvilTransactionJournal(dir,owner);
        j.prepare(new AnvilTransactionJournal.Intent(UUID.randomUUID(),owner,s.encode()));
        assertThrows(IOException.class,j::pendingSnapshot);assertFalse(j.healthy());
        assertThrows(IllegalStateException.class,()->j.complete(op));
    }
    @Test void invalidExperienceAndNoChangeAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new AnvilEscrowSnapshot.Experience(1,0,Float.NaN));
        assertThrows(IllegalArgumentException.class,()->new AnvilEscrowSnapshot.Experience(1,0,1));
        var xp=new AnvilEscrowSnapshot.Experience(1,0,0);
        assertThrows(IllegalArgumentException.class,()->new AnvilEscrowSnapshot(op,owner,0,xp,xp,
                bytes("a"),bytes("b"),bytes("c"),bytes("d"),bytes("same"),bytes("same")));
    }
}

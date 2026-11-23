package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.AnvilTransactionJournal;
import ru.cherdakmd.chrdkregister.storage.StorageIo;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnvilTransactionJournalTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(), operation=UUID.randomUUID();
    final AnvilTransactionJournal.Intent intent=new AnvilTransactionJournal.Intent(operation,owner,"original+result+xp");
    AnvilTransactionJournal journal() throws IOException { return new AnvilTransactionJournal(dir,owner); }
    @Test void persistsAndReopensPendingThenRequiresExactOperationToComplete() throws Exception {
        var j=journal();j.prepare(intent);var reopened=journal();assertEquals(intent,reopened.pending());
        assertThrows(IllegalStateException.class,()->reopened.prepare(intent));
        assertThrows(IllegalArgumentException.class,()->reopened.complete(UUID.randomUUID()));
        reopened.complete(operation);assertNull(journal().pending());
    }
    @Test void unknownWriteOutcomeIsQuarantinedAndRestartSeesPending() throws Exception {
        var j=new AnvilTransactionJournal(dir,owner,new StorageIo(5000),()->{throw new IOException("after replace");});
        assertThrows(IOException.class,()->j.prepare(intent));assertFalse(j.healthy());
        assertThrows(IllegalStateException.class,()->j.prepare(intent));assertEquals(intent,journal().pending());
    }
    @Test void staleInstanceCannotOverwriteAnotherInstance() throws Exception {
        var first=journal();var stale=journal();first.prepare(intent);
        assertThrows(IOException.class,()->stale.prepare(new AnvilTransactionJournal.Intent(UUID.randomUUID(),owner,"different")));
        assertFalse(stale.healthy());assertEquals(intent,journal().pending());
    }
    @Test void wrongOwnerOrMalformedJournalNeverOverwritesEvidence() throws Exception {
        var j=journal();assertThrows(IllegalArgumentException.class,()->j.prepare(new AnvilTransactionJournal.Intent(operation,UUID.randomUUID(),"data")));
        Path file=dir.resolve(owner+".json");String bad="{\"schema\":1,\"schema\":1,\"owner\":\""+owner+"\",\"pending\":null}";
        Files.writeString(file,bad);assertThrows(IOException.class,this::journal);assertEquals(bad,Files.readString(file));
    }
    @Test void intentBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,()->new AnvilTransactionJournal.Intent(operation,owner,""));
        assertThrows(IllegalArgumentException.class,()->new AnvilTransactionJournal.Intent(operation,owner,"я".repeat(3*1024*1024)));
    }
}

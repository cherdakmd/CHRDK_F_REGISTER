package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChargeJournalTest {
    @TempDir Path dir;
    final UUID lineage=UUID.randomUUID(), owner=UUID.randomUUID();
    ChargeJournal journal() throws Exception { return new ChargeJournal(dir,"lottery",lineage); }
    BankCharge prepare(ChargeJournal j) throws Exception { return j.prepare(owner,1,1,0,0,"inventory","inventory","before","after"); }
    @Test void intentSurvivesRestartAndCompletionAdvancesExactlyOnce() throws Exception {
        var c=prepare(journal()); var j=journal(); assertEquals(c,j.pending().payment()); assertEquals(1,j.nextSequence());
        assertThrows(IllegalStateException.class,()->prepare(j)); j.complete(c); assertNull(journal().pending()); assertEquals(2,journal().nextSequence());
        assertThrows(IllegalArgumentException.class,()->j.complete(c));
    }
    @Test void verifiedMissingRollbackReusesSequenceButNotTransaction() throws Exception {
        var j=journal(); var c=prepare(j); j.rollback(c); var next=prepare(journal()); assertEquals(1,next.sequence()); assertNotEquals(c.transaction(),next.transaction());
    }
    @Test void prepareAfterRenameFailureIsNotAbsenceOfIntent() throws Exception {
        journal(); var j=new ChargeJournal(dir,"lottery",lineage,()->{throw new IOException("after rename");});
        assertThrows(IOException.class,()->prepare(j)); assertFalse(j.healthy()); assertNotNull(journal().pending()); assertThrows(IllegalStateException.class,()->prepare(j));
    }
    @Test void completionAfterRenameFailureReloadsAcknowledgementWithoutRepeatingExpense() throws Exception {
        var c=prepare(journal()); var j=new ChargeJournal(dir,"lottery",lineage,()->{throw new IOException("after rename");});
        assertThrows(IOException.class,()->j.complete(c)); assertFalse(j.healthy()); assertNull(journal().pending()); assertEquals(2,journal().nextSequence());
    }
    @Test void corruptionLineageAndHashMismatchAreRejectedWithoutReplacingFile() throws Exception {
        prepare(journal()); String original=Files.readString(dir.resolve("bank-charge.json"));
        assertThrows(IOException.class,()->new ChargeJournal(dir,"lottery",UUID.randomUUID()));
        var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.getAsJsonObject("pending").addProperty("afterState","tamper");
        String bad=json.toString(); Files.writeString(dir.resolve("bank-charge.json"),bad); assertThrows(IOException.class,this::journal); assertEquals(bad,Files.readString(dir.resolve("bank-charge.json")));
    }
    @Test void fractionalSequenceAndMissingFieldsAreRejected() throws Exception {
        journal(); var file=dir.resolve("bank-charge.json"); String original=Files.readString(file);
        var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.addProperty("sequence",0.5); Files.writeString(file,json.toString()); assertThrows(IOException.class,this::journal);
        json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.remove("sequence"); Files.writeString(file,json.toString()); assertThrows(IOException.class,this::journal);
    }
    @Test void cashRequiresInventoryDifferenceAndSourceMustAlwaysAdvance() throws Exception {
        var j=journal(); assertThrows(IllegalArgumentException.class,()->j.prepare(owner,1,0,1,0,"same","same","before","after"));
        assertThrows(IllegalArgumentException.class,()->j.prepare(owner,1,1,0,0,"i","i","same","same")); assertNull(j.pending());
    }
}

package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Отказ Claims 2.0.3: adopt-пути lineage оставлял bank-charge.json со старым lineage,
 *  повторное открытие журнала падало на сверке «journal lineage», плагин отключался.
 *  bank-charge.json обязан переопознаваться вместе с paid-actions.json — только пока
 *  нет ни одного финансового обязательства (fail-closed сохраняется). */
class ChargeLineageAdoptTest {
    @TempDir Path dir;
    final String source = "claim-teleports";
    final UUID payer = UUID.randomUUID(), bank = UUID.randomUUID();

    @Test void bankRegisteredLineageAdoptsAcrossBothFiles() throws Exception {
        var store = new PaidActionStore(dir);
        UUID local = store.lineage();
        new ChargeJournal(dir, source, local);
        var log = new AtomicReference<String>();
        PaidActions.reconcileLineage(dir, source, store, bank, log::set);
        assertEquals(bank, store.lineage());
        assertNotNull(log.get());
        assertEquals(bank, ChargeJournal.recordedLineage(dir));
        assertEquals(1, new ChargeJournal(dir, source, bank).nextSequence());
        assertThrows(IOException.class, () -> new ChargeJournal(dir, source, local));
    }

    @Test void interruptedAdoptionHealsStaleJournalFile() throws Exception {
        var store = new PaidActionStore(dir);
        UUID stale = store.lineage();
        new ChargeJournal(dir, source, stale);
        store.adoptLineage(bank); // как успело сохраниться до падения Claims
        PaidActions.reconcileLineage(dir, source, store, bank, null);
        assertEquals(bank, ChargeJournal.recordedLineage(dir));
        assertEquals(1, new ChargeJournal(dir, source, bank).nextSequence());
        assertThrows(IOException.class, () -> new ChargeJournal(dir, source, stale));
    }

    @Test void committedJournalNeverReidentifies() throws Exception {
        var store = new PaidActionStore(dir);
        UUID local = store.lineage();
        var j = new ChargeJournal(dir, source, local);
        j.prepare(payer, 1, 1, 0, 0, "inventory", "inventory", "before", "after");
        PaidActions.reconcileLineage(dir, source, store, bank, null);
        assertNotEquals(bank, store.lineage());
        assertEquals(local, ChargeJournal.recordedLineage(dir));
        assertNotNull(new ChargeJournal(dir, source, local).pending());
    }

    @Test void financialObligationsPreventAnyHeal() throws Exception {
        var store = new PaidActionStore(dir);
        store.markRegistered();
        new ChargeJournal(dir, source, UUID.randomUUID()); // файл с чужим lineage
        var action = new PaidActionStore.Action(UUID.randomUUID(), payer, payer, 7, "rtp", "world:1:64:2", PaidActionStore.Phase.READY);
        store.publish(store.snapshot(), store.preview(action));
        assertThrows(IOException.class, () -> PaidActions.reconcileLineage(dir, source, store, bank, null));
    }

    @Test void corruptJournalFileFailsClosed() throws Exception {
        var store = new PaidActionStore(dir);
        Files.writeString(dir.resolve("bank-charge.json"), "{corrupt");
        assertThrows(IOException.class, () -> PaidActions.reconcileLineage(dir, source, store, bank, null));
    }

    @Test void unknownExternalLeavesAlignedFilesUntouched() throws Exception {
        var store = new PaidActionStore(dir);
        UUID local = store.lineage();
        new ChargeJournal(dir, source, local);
        PaidActions.reconcileLineage(dir, source, store, null, null);
        assertEquals(local, store.lineage());
        assertEquals(local, ChargeJournal.recordedLineage(dir));
    }

    @Test void paidActionsReconcilesLineageBeforeOpeningTheJournal() throws Exception {
        String code = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java"));
        String init = code.substring(code.indexOf("boolean bankOnly) throws IOException"), code.indexOf("public PaidActionStore store()"));
        assertTrue(init.indexOf("reconcileLineage(") >= 0 && init.indexOf("reconcileLineage(") < init.indexOf("new ChargeJournal("));
    }
}

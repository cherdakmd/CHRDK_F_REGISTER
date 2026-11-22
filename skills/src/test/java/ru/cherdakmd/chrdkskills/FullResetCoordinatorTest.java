package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class FullResetCoordinatorTest {
    @TempDir Path folder;
    static final String OWNER = "11111111-2222-3333-4444-555555555555";
    static final String EPOCH = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    final Map<String, Long> journal = new LinkedHashMap<>();
    boolean markAvailable = true, generatorAvailable = true;
    String generatorEpoch = "legacy";
    int generatorResets;
    final FullResetCoordinator.Ledger ledger = new FullResetCoordinator.Ledger() {
        public Map<String, Long> reservations() { return Map.copyOf(journal); }
        public boolean accept(String event, long amount) { journal.putIfAbsent(event, amount); return true; }
        public boolean mark(String event) { if (!markAvailable) return false; journal.putIfAbsent(event, 0L); return true; }
    };
    final FullResetCoordinator.Generator generator = (previous, next, count) -> {
        if (!generatorAvailable) return false;
        if (generatorEpoch.equals(next)) return true;
        if (!generatorEpoch.equals(previous) || count != 1) return false;
        generatorEpoch = next; generatorResets++;
        return true;
    };
    SkillStore load() { var s = new SkillStore(folder, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    SkillStore ready() {
        var s = load(); s.addXp(OWNER, SkillType.MINING, 100); s.flush();
        assertEquals(1, s.beginPaidRebirth(OWNER, 42));
        assertTrue(RebirthBankPayout.reconcile(s, OWNER, ledger));
        s.addXp(OWNER, SkillType.MINING, 55); s.flush(); return s;
    }

    @Test void markerFailureLeavesIntentAndCanResume() {
        var s = ready(); markAvailable = false;
        assertFalse(FullResetCoordinator.start(s, OWNER, EPOCH, ledger, generator));
        s = load(); assertEquals(EPOCH, s.pendingResetEpoch(OWNER));
        assertEquals(55, s.xp(OWNER, SkillType.MINING)); assertEquals(0, generatorResets);
        markAvailable = true;
        assertTrue(FullResetCoordinator.resume(s, OWNER, ledger, generator));
        assertEquals(0, load().xp(OWNER, SkillType.MINING));
        assertEquals(42L, journal.get("rebirth:1"));
        assertEquals(0L, journal.get("reset:" + EPOCH));
        assertEquals(1, generatorResets);
    }

    @Test void restartAfterGeneratorResetAndFailedSkillCheckpointDoesNotResetTwice() throws Exception {
        var s = ready();
        Path tmp = folder.resolve("skills.json.tmp");
        var blockingGenerator = (FullResetCoordinator.Generator) (previous, next, count) -> {
            if (!generator.reset(previous, next, count)) return false;
            try { Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x"); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            return true;
        };
        assertFalse(FullResetCoordinator.start(s, OWNER, EPOCH, ledger, blockingGenerator));
        assertEquals(EPOCH, load().pendingResetEpoch(OWNER));
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertTrue(FullResetCoordinator.resume(load(), OWNER, ledger, generator));
        assertEquals(1, generatorResets);
        assertEquals(0, load().rebirths(OWNER));
        assertTrue(RebirthBankPayout.reconcile(load(), OWNER, ledger));
    }

    @Test void missingReceiptOrMarkerMismatchNeverCallsGenerator() {
        var s = ready(); generatorAvailable = false;
        assertFalse(FullResetCoordinator.start(s, OWNER, EPOCH, ledger, generator));
        generatorAvailable = true;
        journal.put("reset:" + EPOCH, 5L);
        assertFalse(FullResetCoordinator.resume(load(), OWNER, ledger, generator));
        journal.put("reset:" + EPOCH, 0L); journal.remove("rebirth:1");
        assertFalse(FullResetCoordinator.resume(load(), OWNER, ledger, generator));
        assertEquals(0, generatorResets);
        assertEquals(55, load().xp(OWNER, SkillType.MINING));
    }
}

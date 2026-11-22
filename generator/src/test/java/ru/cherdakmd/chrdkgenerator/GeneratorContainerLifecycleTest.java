package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorContainerLifecycleTest {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID(), core = UUID.randomUUID();
    GeneratorStore source() { var s = new GeneratorStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    GeneratorContainerStore target() { var s = new GeneratorContainerStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    GeneratorStore ready() throws Exception { var s = source(); s.markRegistered(); s.install(owner, core, "world", 0, 64, 0, 100, 3); s.flush(); return s; }
    @Test void bindRecoversAfterIntentAndRefusesMissingActiveContainer() throws Exception {
        var s = ready(); assertTrue(s.beginContainerBinding(owner, 0, core, null, 0));
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(source(), target(), owner, 0, null, 0, 100));
        assertNull(source().containerBinding(owner, 0).transition());
        try { Files.delete(dir.resolve("generator-containers.json")); } catch (Exception e) { fail(e); }
        assertEquals(GeneratorContainerLifecycle.Result.CONFLICT, GeneratorContainerLifecycle.bind(source(), target(), owner, 0, null, 0, 100));
    }
    @Test void initialFuelClockStartsAtActivationNotOldLegacyTimestamp() throws Exception {
        var s = ready(); var t = target();
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 50_000));
        assertEquals(50_000, target().view(owner, 0).lastCalcMillis());
    }
    @Test void releaseOnlyEmptyAndRecoversAfterDestinationReceipt() throws Exception {
        var s = ready(); var t = target();
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        var view = t.view(owner, 0); var slots = new java.util.ArrayList<>(view.slots());
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 1));
        assertTrue(t.settle(owner, 0, core, view.revision(), slots, 1000, 100));
        assertEquals(GeneratorContainerLifecycle.Result.NOT_EMPTY, GeneratorContainerLifecycle.release(s, t, owner, 0));
        assertThrows(IllegalStateException.class, () -> s.remove(owner, 0, 200));
        view = t.view(owner, 0); slots.set(36, null);
        assertTrue(t.settle(owner, 0, core, view.revision(), slots, 1000, 100));
        assertTrue(s.beginContainerRelease(owner, 0, core));
        var binding = s.containerBinding(owner, 0);
        assertTrue(t.removeEmpty(owner, 0, core, null, binding.releaseId()));
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.release(source(), target(), owner, 0));
        assertNull(source().containerBinding(owner, 0));
        assertNull(target().view(owner, 0));
        assertEquals(0, source().remove(owner, 0, 200));
    }
    @Test void missingTargetDuringReleaseIsNotMistakenForSuccessfulRemoval() throws Exception {
        var s = ready(); var t = target();
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        assertTrue(s.beginContainerRelease(owner, 0, core));
        Files.delete(dir.resolve("generator-containers.json"));
        assertEquals(GeneratorContainerLifecycle.Result.CONFLICT, GeneratorContainerLifecycle.release(source(), target(), owner, 0));
        assertNotNull(source().containerBinding(owner, 0));
    }
    @Test void releasePreservesSalesHistoryWhenSameCoreIsInstalledAgain() throws Exception {
        var s = ready(); var t = target();
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        var slots = new java.util.ArrayList<>(t.view(owner, 0).slots());
        slots.set(0, new GeneratorContainerStore.Stack("DIAMOND", 1));
        assertTrue(t.settle(owner, 0, core, 0, slots, 0, 100));
        var ledger = new GeneratorContainerSaleTest.Ledger();
        assertEquals(GeneratorContainerSale.Result.SOLD, GeneratorContainerSale.run(t, owner, 0, true, (m, n) -> 100, ledger));
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.release(s, t, owner, 0));
        assertEquals(0, s.remove(owner, 0, 200));
        s.flush(); s.install(owner, core, "world", 1, 64, 0, 300, 3); s.flush();
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        assertEquals(1, target().saleState(owner, 0).sequence());
        assertEquals(GeneratorContainerSale.Result.IDLE, GeneratorContainerSale.run(target(), owner, 0, false, (m, n) -> 100, ledger));
    }
    @Test void rebirthKeepsOverflowAndFrozenDropsAcrossRestart() throws Exception {
        var s = ready(); var t = target();
        s.addBunker(owner, 0, 3000, 100); s.setTier(owner, 0, 3); s.flush();
        var drops = java.util.List.of(new GeneratorDrops.Drop("GOLD_INGOT", 1, false));
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, drops, 0, 100));
        assertEquals(drops, source().legacyDrops(owner, 0).drops());
        assertEquals(GeneratorBunkerMigration.Result.FULL, GeneratorBunkerMigration.run(s, t, owner, 0,
                () -> GeneratorDrops.roll(s.legacyDrops(owner, 0).drops(), 0, new java.util.Random()), m -> 64));
        assertEquals(696, source().entry(owner, 0).bunker());
        assertTrue(s.rebirthKeepingBunker(owner, 1));
        assertTrue(s.rebirthKeepingBunker(owner, 1));
        var restored = source();
        assertEquals(1, restored.entry(owner, 0).tier());
        assertEquals(1, restored.entry(owner, 0).rebirths());
        assertEquals(696, restored.entry(owner, 0).bunker());
        assertEquals(drops, restored.legacyDrops(owner, 0).drops());
        assertEquals("GOLD_INGOT", target().view(owner, 0).slots().get(0).material());
    }
    @Test void rebirthCountBaselineAndPendingSurviveRestart() throws Exception {
        var s = ready(); var t = target();
        assertTrue(s.observeSkillRebirth(owner, 4));
        assertEquals(0, source().requestedSkillRebirth(owner));
        assertTrue(s.observeSkillRebirth(owner, 6));
        assertEquals(6, source().requestedSkillRebirth(owner));
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        assertTrue(source().rebirthKeepingBunker(owner, 6));
        assertEquals(2, source().entry(owner, 0).rebirths());
        assertEquals(0, source().requestedSkillRebirth(owner));
        assertTrue(source().observeSkillRebirth(owner, 6));
        assertEquals(0, source().requestedSkillRebirth(owner));
    }
    @Test void oldSourceWithLiveContainerFailsClosed() throws Exception {
        var s = ready(); var t = target();
        Path backup = dir.resolve("backup"); Files.copy(dir.resolve("generator.json"), backup);
        assertEquals(GeneratorContainerLifecycle.Result.READY, GeneratorContainerLifecycle.bind(s, t, owner, 0, null, 0, 100));
        Files.copy(backup, dir.resolve("generator.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(GeneratorContainerLifecycle.Result.CONFLICT, GeneratorContainerLifecycle.bind(source(), target(), owner, 0, null, 0, 100));
    }
}

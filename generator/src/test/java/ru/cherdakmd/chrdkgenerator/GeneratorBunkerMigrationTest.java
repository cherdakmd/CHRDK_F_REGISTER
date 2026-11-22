package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorBunkerMigrationTest {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID(), core = UUID.randomUUID();
    GeneratorStore source() { var s = new GeneratorStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    GeneratorContainerStore target() { var s = new GeneratorContainerStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    GeneratorStore ready(long units) throws Exception {
        var s = source(); s.markRegistered(); s.install(owner, core, "world", 0, 64, 0, 1, 3);
        s.addBunker(owner, 0, units, 100); s.flush(); return s;
    }
    GeneratorBunkerMigration.Result run(GeneratorStore s, GeneratorContainerStore t) {
        return GeneratorBunkerMigration.run(s, t, owner, 0, () -> "COBBLESTONE", x -> 64);
    }
    long stored(GeneratorContainerStore s) {
        return s.view(owner, 0).slots().stream().filter(java.util.Objects::nonNull).mapToLong(GeneratorContainerStore.Stack::amount).sum();
    }
    @Test void migratesOnceAndRetainsConcreteItems() throws Exception {
        var s = ready(100); var t = target();
        assertEquals(GeneratorBunkerMigration.Result.READY, run(s, t));
        assertEquals(0, source().entry(owner, 0).bunker());
        assertEquals(100, stored(target()));
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals(100, stored(target()));
    }
    @Test void capacityNeverDiscardsLegacyBalance() throws Exception {
        var s = ready(3000); var t = target();
        assertEquals(GeneratorBunkerMigration.Result.FULL, run(s, t));
        assertEquals(2304, stored(target()));
        assertEquals(696, source().entry(owner, 0).bunker());
        assertEquals(GeneratorBunkerMigration.Result.FULL, run(source(), target()));
        assertEquals(696, source().entry(owner, 0).bunker());
    }
    @Test void restartImmediatelyAfterIntentUsesFrozenItems() throws Exception {
        var s = ready(20); var t = target(); assertTrue(t.create(owner, 0, core, 1));
        var view = t.view(owner, 0);
        var plan = GeneratorBunkerMigration.plan(view.slots(), 20, () -> "GOLD_INGOT", x -> 64);
        var intent = new GeneratorStore.ContainerMigration(UUID.randomUUID(), core, 20, view.revision(), null, plan.slots());
        assertTrue(s.prepareContainerMigration(owner, 0, intent));
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals("GOLD_INGOT", target().view(owner, 0).slots().get(0).material());
        assertEquals(20, stored(target()));
        assertEquals(0, source().entry(owner, 0).bunker());
    }
    @Test void restartAfterSourceIntentOrDestinationApplyNeverRerolls() throws Exception {
        var s = ready(20); var t = target(); assertTrue(t.create(owner, 0, core, 1));
        var view = t.view(owner, 0);
        var plan = GeneratorBunkerMigration.plan(view.slots(), 20, () -> "GOLD_INGOT", x -> 64);
        var intent = new GeneratorStore.ContainerMigration(UUID.randomUUID(), core, 20, view.revision(), null, plan.slots());
        assertTrue(s.prepareContainerMigration(owner, 0, intent));
        assertTrue(t.applyMigration(owner, 0, intent));
        assertEquals(20, source().entry(owner, 0).bunker());
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals("GOLD_INGOT", target().view(owner, 0).slots().get(0).material());
        assertEquals(20, stored(target()));
        assertEquals(0, source().entry(owner, 0).bunker());
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals(20, stored(target()));
    }
    @Test void failedDestinationWriteLeavesFrozenSourceIntent() throws Exception {
        var s = ready(20); var t = target();
        assertTrue(t.create(owner, 0, core, 1));
        Path tmp = dir.resolve("generator-containers.json.tmp");
        Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x");
        assertEquals(GeneratorBunkerMigration.Result.WAIT, run(s, t));
        assertEquals(20, source().entry(owner, 0).bunker());
        assertNotNull(source().containerMigration(owner, 0));
        assertEquals(0, stored(target()));
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals(0, source().entry(owner, 0).bunker());
        assertEquals(20, stored(target()));
    }
    @Test void failureAfterDestinationWriteRecoversWithoutDuplicate() throws Exception {
        var s = ready(20); var t = target(); assertTrue(t.create(owner, 0, core, 1));
        var view = t.view(owner, 0);
        var plan = GeneratorBunkerMigration.plan(view.slots(), 20, () -> "GOLD_INGOT", x -> 64);
        var intent = new GeneratorStore.ContainerMigration(UUID.randomUUID(), core, 20, view.revision(), null, plan.slots());
        assertTrue(s.prepareContainerMigration(owner, 0, intent));
        assertTrue(t.applyMigration(owner, 0, intent));
        Path tmp = dir.resolve("generator.json.tmp");
        Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x");
        assertEquals(GeneratorBunkerMigration.Result.WAIT, run(s, t));
        assertEquals(20, source().entry(owner, 0).bunker());
        assertEquals(20, stored(target()));
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertEquals(GeneratorBunkerMigration.Result.READY, run(source(), target()));
        assertEquals(0, source().entry(owner, 0).bunker());
        assertEquals(20, stored(target()));
    }
    @Test void restoredOldSourceOrLostDestinationStopsInsteadOfDuplicating() throws Exception {
        var s = ready(3); var t = target();
        Path old = dir.resolve("old.json"); Files.copy(dir.resolve("generator.json"), old);
        assertEquals(GeneratorBunkerMigration.Result.READY, run(s, t));
        Files.copy(old, dir.resolve("generator.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(GeneratorBunkerMigration.Result.CONFLICT, run(source(), target()));
        assertEquals(3, stored(target()));
        assertEquals(3, source().entry(owner, 0).bunker());
    }
    @Test void fuelCellsAreUntouchedAndItemStackLimitsRespected() {
        var before = new java.util.ArrayList<GeneratorContainerStore.Stack>(java.util.Collections.nCopies(45, null));
        before.set(36, new GeneratorContainerStore.Stack("COAL", 9));
        var plan = GeneratorBunkerMigration.plan(before, 20, () -> "ENDER_PEARL", x -> 16);
        assertEquals(20, plan.units());
        assertEquals(new GeneratorContainerStore.Stack("ENDER_PEARL", 16), plan.slots().get(0));
        assertEquals(new GeneratorContainerStore.Stack("ENDER_PEARL", 4), plan.slots().get(1));
        assertEquals(before.get(36), plan.slots().get(36));
    }
}

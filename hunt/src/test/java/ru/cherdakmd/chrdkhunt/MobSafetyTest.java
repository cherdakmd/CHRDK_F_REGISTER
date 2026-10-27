package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.MobProgressionKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MobSafetyTest {
    @TempDir Path dir;
    @Test void zoneRangeMatchesPersonalLevelAtBothExtremes() {
        for (String world : List.of("NORMAL", "NETHER", "THE_END")) for (double distance : new double[]{0, 1024, 100_000, Double.NaN}) {
            var range = MobProgressionPolicy.range(world, distance);
            assertEquals(range.min(), MobProgressionPolicy.level(world, distance, 10));
            int level = MobProgressionPolicy.level(world, distance, 1000);
            assertTrue(level <= range.max() && level >= range.min());
        }
    }
    @Test void nearestPlayerTiesAreIndependentOfIterationOrderAndIncludeBoundary() {
        UUID first = new UUID(0, 1), last = new UUID(0, 2);
        assertTrue(MobProgressionPolicy.nearer(16384, last, 16384, null));
        assertTrue(MobProgressionPolicy.nearer(10, first, 10, last));
        assertFalse(MobProgressionPolicy.nearer(10, last, 10, first));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 16385})
            assertFalse(MobProgressionPolicy.nearer(bad, first, Double.MAX_VALUE, null));
    }
    @Test void unsafeHealthNeverProducesAWriteOrRevivesZeroHealth() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1}) {
            assertTrue(Double.isNaN(MobProgressionPolicy.scaledHealth(10, bad, 20)));
            assertTrue(Double.isNaN(MobProgressionPolicy.scaledHealth(10, 20, bad)));
            assertTrue(Double.isNaN(MobProgressionPolicy.scaledHealth(bad, 20, 40)));
        }
        assertEquals(20, MobProgressionPolicy.scaledHealth(10, 20, 40));
        assertEquals(5, MobProgressionPolicy.scaledHealth(10, 20, 10));
        assertTrue(MobProgressionPolicy.scaledHealth(.001, 20, 10) <= .001);
    }
    @Test void failingOrStaleSkillsFallsBackWithoutChangingGeographicalMinimum() {
        assertEquals(10, MobProgression.skills(() -> { throw new IllegalStateException(); }));
        assertEquals(10, MobProgression.skills(() -> { throw new NoSuchMethodError(); }));
        assertEquals(10, MobProgression.skills(() -> -100));
        assertEquals(1000, MobProgression.skills(() -> Integer.MAX_VALUE));
        assertEquals(12, MobProgressionPolicy.level("THE_END", 0, MobProgression.skills(() -> { throw new RuntimeException(); })));
    }
    @Test void armoredVariantDoesNotChangeOtherStatMultipliersAndCannotExceedArmorBudget() {
        assertEquals(6, MobProgressionKeys.MAX_ELITE);
        assertEquals(MobProgressionPolicy.stats(20, 0), MobProgressionPolicy.stats(20, 4));
        assertEquals(4, MobProgressionPolicy.armorBonus(2, 4));
        assertEquals(1, MobProgressionPolicy.armorBonus(11, 4));
        assertEquals(0, MobProgressionPolicy.armorBonus(20, 4));
        assertEquals(0, MobProgressionPolicy.armorBonus(Double.NaN, 4));
        assertEquals(0, MobProgressionPolicy.armorBonus(2, 0));
        assertTrue(MobProgressionPolicy.valid(1, 5)); assertTrue(MobProgressionPolicy.valid(1, 6)); assertFalse(MobProgressionPolicy.valid(1, MobProgressionKeys.MAX_ELITE + 1));
        assertFalse(MobProgressionPolicy.valid(31, 1));
    }
    @Test void shotSnapshotIsFiniteBoundedExpiresAndRejectsFutureTimestamp() {
        assertEquals(1.5, MobProgressionPolicy.shotFactor(1.5, 1000, 31_000));
        assertEquals(1, MobProgressionPolicy.shotFactor(1.5, 1000, 31_001));
        assertEquals(1, MobProgressionPolicy.shotFactor(1.5, 1000, 999));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 1.50001})
            assertEquals(1, MobProgressionPolicy.shotFactor(bad, 1000, 2000));
        assertEquals(1, MobProgressionPolicy.shotFactor(1.5, -1, Long.MAX_VALUE));
    }
    @Test void queueDeduplicatesAndIsActuallyBounded() {
        FakeScheduler scheduler = new FakeScheduler(); MobTaskQueue queue = new MobTaskQueue(scheduler);
        UUID id = UUID.randomUUID(); AtomicInteger calls = new AtomicInteger();
        assertTrue(queue.submit(id, "teleport", calls::incrementAndGet));
        assertFalse(queue.submit(id, "teleport", calls::incrementAndGet));
        assertTrue(queue.contains(id, "teleport"));
        for (int i = 1; i < 4096; i++) assertTrue(queue.submit(new UUID(0, i), "other", calls::incrementAndGet));
        assertFalse(queue.submit(id, "hud", calls::incrementAndGet)); assertEquals(4096, queue.size());
        scheduler.actions.forEach(Runnable::run); assertEquals(4096, calls.get()); assertEquals(0, queue.size());
    }
    @Test void canceledOldTicketCannotConsumeReplacementWithSameKey() {
        FakeScheduler scheduler = new FakeScheduler(); MobTaskQueue queue = new MobTaskQueue(scheduler);
        UUID id = UUID.randomUUID(); AtomicInteger calls = new AtomicInteger();
        queue.submit(id, "hud", calls::incrementAndGet); queue.clear();
        assertEquals(1, scheduler.canceled.get());
        queue.submit(id, "hud", calls::incrementAndGet);
        scheduler.actions.getFirst().run(); assertEquals(0, calls.get()); assertEquals(1, queue.size());
        scheduler.actions.getLast().run(); assertEquals(1, calls.get()); assertEquals(0, queue.size());
        queue.stop(); assertFalse(queue.submit(id, "hud", calls::incrementAndGet));
    }
    @Test void schedulerFailureDoesNotLeakQueueCapacity() {
        MobTaskQueue queue = new MobTaskQueue(action -> { throw new IllegalStateException(); });
        assertThrows(IllegalStateException.class, () -> queue.submit(UUID.randomUUID(), "test", () -> { }));
        assertEquals(0, queue.size());
    }
    @Test void observationsAndHudRoundTripWithoutAwardingKillsOrCurrency() {
        HuntStore store = new HuntStore(dir, null); UUID id = UUID.randomUUID();
        assertTrue(store.mobHud(id)); assertTrue(store.observeMob(id, "Игрок", 12, 4));
        assertFalse(store.observeMob(id, "Игрок", 12, 4));
        assertFalse(store.observeMob(id, "Игрок", 31, 0)); assertFalse(store.observeMob(id, "Игрок", 2, MobProgressionKeys.MAX_ELITE + 1));
        store.mobHud(id, "Игрок", false); store.saveNow();
        HuntStore loaded = new HuntStore(dir, null); loaded.load();
        assertEquals(Set.of(12), loaded.observedLevels(id)); assertEquals(Set.of(4), loaded.observedElites(id));
        assertFalse(loaded.mobHud(id)); assertEquals(0, loaded.coins(id));
        assertEquals(0, loaded.player(id).killsTotal()); assertEquals(0, loaded.bestiarySeen(id));
        assertTrue(loaded.bestiaryAwards(id).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> loaded.observedLevels(id).add(1));
    }
    @Test void oldProfilesDefaultToEnabledHudAndCorruptCollectionsAreBounded() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(dir.resolve("hunt.json"), "{\"players\":{\"" + id + "\":{\"name\":\"Игрок\",\"coins\":123,\"observed-levels\":[0,1,1,2.5,30,31,999999999999],\"observed-elites\":[-1,0,1,4,5,6,7]}}}");
        HuntStore store = new HuntStore(dir, null); store.load();
        assertEquals(123, store.coins(id)); assertTrue(store.mobHud(id));
        assertEquals(Set.of(1,30), store.observedLevels(id)); assertEquals(Set.of(1,4,5,6), store.observedElites(id));
        for (int i = 1; i <= 30; i++) store.observeMob(id, "Игрок", i, i % (MobProgressionKeys.MAX_ELITE + 1));
        assertEquals(30, store.observedLevels(id).size()); assertEquals(MobProgressionKeys.MAX_ELITE, store.observedElites(id).size());
    }
    @Test void hudAndInspectionHaveFinalCancellationAuthDistanceAndCleanupContracts() throws Exception {
        String source = source("MobProgression");
        for (String guard : List.of("event.isCancelled() || !Double.isFinite(event.getFinalDamage())", "event.getFinalDamage() <= 0",
                "!observer(player)", "tasks.clear()", "tasks.stop()", "WATCH_LIMIT = 8192", "WATCH_BUDGET = 128", "watchTask.cancel()",
                "mob.getEntitySpawnReason()", "data.has(level, PersistentDataType.INTEGER)", "data.remove(level); data.remove(elite); data.remove(origin)")) assertTrue(source.contains(guard), guard);
        String report = source("MobReport");
        for (String guard : List.of("!player.hasPermission(\"chrdkhunt.use\")", "!plugin.coreApi().isAuthenticated(player)",
                "FluidCollisionMode.NEVER, true, .15", "rayTrace(eye, eye.getDirection(), 16")) assertTrue(report.contains(guard), guard);
        assertFalse(report.contains("addItem(")); assertFalse(report.contains("award("));
    }
    @Test void rangedDamageUsesLaunchSnapshotNotCurrentMobLevel() throws Exception {
        String source = source("MobProgression"); String damage = source.substring(source.indexOf("public void ranged"));
        assertTrue(source.contains("data.set(shotOwner")); assertTrue(source.contains("data.set(shotEpoch"));
        assertTrue(damage.contains("shotFactor(data.get(shotFactor")); assertFalse(damage.contains("MobProgressionPolicy.stats("));
        assertTrue(damage.contains("epoch.equals(data.get(shotEpoch")); assertTrue(damage.contains("mob.getUniqueId().toString().equals"));
    }
    @Test void messagesContainEveryEliteAndNewCommand() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream); var messages = Messages.from(stream, dir.resolve("messages.yml"), Logger.getLogger("MobSafetyTest"));
            for (int i = 0; i <= ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE; i++) assertTrue(messages.has("mob-variant-" + i));
            for (String key : List.of("mob-danger", "mob-inspect", "mob-observations", "mob-elite-collection", "mob-hud-state", "mob-bestiary-levels")) assertTrue(messages.has(key), key);
        }
    }
    private String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/" + name + ".java")); }
    private static class FakeScheduler implements MobTaskQueue.Scheduler {
        final List<Runnable> actions = new ArrayList<>(); final AtomicInteger canceled = new AtomicInteger();
        public MobTaskQueue.Handle submit(Runnable action) { actions.add(action); return canceled::incrementAndGet; }
    }
}

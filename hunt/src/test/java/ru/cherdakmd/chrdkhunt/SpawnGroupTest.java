package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpawnGroupTest {
    @Test void disabledAndSoloRetainExactNearestDefault() {
        var disabled = new SpawnGroup(SpawnGroup.Settings.bounded(false, 24, 8));
        disabled.offer(UUID.randomUUID(), 0, 1000);
        assertEquals(0, disabled.size()); assertEquals(71, disabled.effectiveSkills(71));
        var solo = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 8));
        assertEquals(71, solo.effectiveSkills(71));
        solo.offer(UUID.randomUUID(), 4, 1000);
        assertEquals(71, solo.effectiveSkills(71));
    }
    @Test void groupUsesBoundedIntegerMeanNotSumOrHighest() {
        var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 8));
        group.offer(UUID.randomUUID(), 1, 10); group.offer(UUID.randomUUID(), 4, 101);
        assertEquals(55, group.effectiveSkills(10));
    }
    @Test void closestMembersAndUuidTiesAreIndependentOfIterationOrder() {
        List<Integer> order = new ArrayList<>(); for (int i = 1; i <= 12; i++) order.add(i);
        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(order, new Random(seed));
            var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 2));
            for (int i : order) group.offer(new UUID(0, i), 4, i * 10);
            assertEquals(2, group.size()); assertEquals(15, group.effectiveSkills(1000));
        }
        var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 2));
        group.offer(new UUID(0, 1), 400, 1000);
        group.offer(new UUID(0, 2), 1, 10); group.offer(new UUID(0, 3), 2, 30);
        assertEquals(20, group.effectiveSkills(1000));
    }
    @Test void duplicatePlayerCannotMultiplyTheirWeight() {
        var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 8)); UUID id = UUID.randomUUID();
        group.offer(id, 1, 10); group.offer(id, 1, 1000);
        assertEquals(1, group.size()); assertEquals(100, group.effectiveSkills(100));
    }
    @Test void finiteSphericalBoundaryAndSettingsCaps() {
        var settings = SpawnGroup.Settings.bounded(true, 24, 8);
        assertTrue(settings.inRange(576)); assertFalse(settings.inRange(576.1));
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) assertFalse(settings.inRange(bad));
        assertEquals(4, SpawnGroup.Settings.bounded(true, -1, 0).radius());
        assertEquals(2, SpawnGroup.Settings.bounded(true, -1, 0).limit());
        assertEquals(32, SpawnGroup.Settings.bounded(true, 999, 999).radius());
        assertEquals(8, SpawnGroup.Settings.bounded(true, 999, 999).limit());
        assertEquals(24, SpawnGroup.Settings.bounded(true, Double.NaN, 8).radius());
        assertEquals(24, SpawnGroup.Settings.bounded(true, Double.POSITIVE_INFINITY, 8).radius());
    }
    @Test void hostileSkillsAndNullIdsCannotOverflowMean() {
        var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 8));
        group.offer(null, 0, 1000); assertEquals(0, group.size());
        group.offer(UUID.randomUUID(), 0, Integer.MAX_VALUE);
        group.offer(UUID.randomUUID(), 0, Integer.MIN_VALUE);
        assertEquals(505, group.effectiveSkills(10));
    }
    @Test void everyWorldStaysInGeographicRangeAndNewcomerWins() {
        var group = new SpawnGroup(SpawnGroup.Settings.bounded(true, 24, 8));
        group.offer(UUID.randomUUID(), 1, 1000); group.offer(UUID.randomUUID(), 2, 1000);
        for (String world : List.of("NORMAL", "NETHER", "THE_END")) for (double distance : new double[]{0, 1024, 100_000}) {
            var range = MobProgressionPolicy.range(world, distance);
            int level = MobProgressionPolicy.level(world, distance, group.effectiveSkills(10));
            assertTrue(level >= range.min() && level <= range.max());
            var protectedSpawn = NewcomerProtection.settings(true, 20, 16).apply(range, level, 4, true);
            assertEquals(range.min(), protectedSpawn.level()); assertEquals(0, protectedSpawn.elite());
        }
    }
    @Test void runtimeKeepsFinalSpawnGuardsVisibilityAndNearestElitePolicy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/MobProgression.java"));
        String spawn = source.substring(source.indexOf("public void spawn("), source.indexOf("private boolean marked("));
        assertTrue(spawn.contains("event.isCancelled()")); assertTrue(spawn.contains("observer(player)"));
        assertTrue(spawn.contains("!player.hasMetadata(\"NPC\")"));
        assertTrue(spawn.contains("!player.isInvulnerable()")); assertTrue(spawn.contains("mob.hasLineOfSight(player)"));
        assertTrue(spawn.contains("group.effectiveSkills(total)"));
        assertTrue(spawn.contains("distance > 512 && total >= 50"));
        assertEquals(2, source.split("group\\.effectiveSkills\\(", -1).length);
        assertTrue(spawn.indexOf("group.effectiveSkills(total)") < spawn.indexOf("newcomers.apply("));
        assertFalse(spawn.contains("setDroppedExp("));
    }
}

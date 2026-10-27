package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NewcomerProtectionTest {
    @Test void disabledModeNeverChangesLevelOrElite() {
        var disabled = NewcomerProtection.settings(false, 20, 16);
        assertFalse(disabled.candidate(10, 0));
        assertEquals(new NewcomerProtection.Spawn(5, 4), disabled.apply(new MobProgressionPolicy.Range(1, 5), 5, 4, true));
    }
    @Test void visibleNearbyNewcomerProtectsMixedGroupEvenWhenNearestIsVeteran() {
        var protection = NewcomerProtection.settings(true, 20, 16);
        assertFalse(protection.candidate(1000, 1)); // closest: veteran
        assertTrue(protection.candidate(10, 100)); // another member, ten blocks away
        var range = MobProgressionPolicy.range("NORMAL", 0);
        int veteranLevel = MobProgressionPolicy.level("NORMAL", 0, 1000);
        assertEquals(5, veteranLevel);
        assertEquals(new NewcomerProtection.Spawn(1, 0), protection.apply(range, veteranLevel, 4, true));
    }
    @Test void absentNewcomerDoesNotChangeNormalRoll() {
        var protection = NewcomerProtection.settings(true, 20, 16);
        assertEquals(new NewcomerProtection.Spawn(5, 4), protection.apply(new MobProgressionPolicy.Range(1, 5), 5, 4, false));
    }
    @Test void skillAndSphericalRadiusBoundariesAreInclusiveAndFinite() {
        var protection = NewcomerProtection.settings(true, 20, 16);
        assertTrue(protection.candidate(10, 0)); assertTrue(protection.candidate(20, 256));
        assertFalse(protection.candidate(21, 0)); assertFalse(protection.candidate(9, 0));
        for (double bad : new double[]{-1, 256.001, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(protection.candidate(10, bad));
    }
    @Test void malformedSettingsAreBounded() {
        assertEquals(10, NewcomerProtection.settings(true, Integer.MIN_VALUE, 0).maxSkills());
        assertEquals(50, NewcomerProtection.settings(true, Integer.MAX_VALUE, 999).maxSkills());
        assertEquals(4, NewcomerProtection.settings(true, 20, -100).radius());
        assertEquals(24, NewcomerProtection.settings(true, 20, 999).radius());
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(16, NewcomerProtection.settings(true, 20, bad).radius());
    }
    @Test void protectionKeepsGeographicalMinimumAndDoesNotAmplifyRewardsOrStats() {
        var protection = NewcomerProtection.settings(true, 20, 16);
        for (String world : new String[]{"NORMAL", "NETHER", "THE_END"})
            for (double distance : new double[]{0, 1024, 100_000}) for (int elite = 0; elite <= 4; elite++) {
                var range = MobProgressionPolicy.range(world, distance);
                int normal = MobProgressionPolicy.level(world, distance, 1000);
                var result = protection.apply(range, normal, elite, true);
                assertEquals(range.min(), result.level()); assertEquals(0, result.elite());
                assertTrue(result.level() <= normal);
                assertTrue(MobProgressionPolicy.bookTier(result.level()) <= MobProgressionPolicy.bookTier(normal));
                var before = MobProgressionPolicy.stats(normal, elite);
                var after = MobProgressionPolicy.stats(result.level(), result.elite());
                assertTrue(after.health() <= before.health()); assertTrue(after.damage() <= before.damage());
                assertTrue(after.speed() <= before.speed());
            }
        assertEquals(12, protection.apply(MobProgressionPolicy.range("THE_END", 0), 16, 1, true).level());
    }
    @Test void runtimeOnlyAppliesProtectionAtFirstSpawnWithAuthenticationPermissionAndVisibility() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/MobProgression.java"));
        int spawn = source.indexOf("public void spawn("), apply = source.indexOf("newcomers.apply("), refresh = source.indexOf("private void refresh(");
        assertTrue(spawn < apply && apply < refresh);
        assertEquals(2, source.split("newcomers\\.apply\\(", -1).length);
        String method = source.substring(spawn, source.indexOf("private boolean marked("));
        assertTrue(method.contains("observer(player)"));
        assertTrue(method.contains("!player.hasMetadata(\"NPC\")"));
        assertTrue(method.contains("!player.isInvulnerable()"));
        assertTrue(method.contains("mob.hasLineOfSight(player)"));
        assertTrue(method.contains("distanceSquared(mob.getLocation())"));
        assertTrue(method.contains("event.isCancelled()"));
        assertTrue(method.contains("!mob.getPersistentDataContainer().getKeys().isEmpty()"));
        assertFalse(method.contains("setTarget("));
    }
}

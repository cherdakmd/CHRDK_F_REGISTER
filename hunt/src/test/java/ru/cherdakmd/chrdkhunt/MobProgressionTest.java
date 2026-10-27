package ru.cherdakmd.chrdkhunt;

import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MobProgressionTest {
    @Test void geographyAndSkillsBothConstrainTheLevel() {
        assertEquals(1, MobProgressionPolicy.level("NORMAL", 0, 10));
        assertEquals(5, MobProgressionPolicy.level("NORMAL", 0, 1000));
        assertEquals(3, MobProgressionPolicy.level("NORMAL", 1024, 10));
        assertEquals(7, MobProgressionPolicy.level("NORMAL", 1024, 1000));
        assertEquals(6, MobProgressionPolicy.level("NETHER", 0, 10));
        assertEquals(12, MobProgressionPolicy.level("THE_END", 0, 10));
    }
    @Test void hostileInputsAndFarCoordinatesCannotOverflow() {
        for (String world : new String[]{"NORMAL", "NETHER", "THE_END", "CUSTOM"}) {
            for (double distance : new double[]{-10, 0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
                for (int skills : new int[]{Integer.MIN_VALUE, 0, 10, 50, 1000, Integer.MAX_VALUE}) {
                    int level = MobProgressionPolicy.level(world, distance, skills);
                    assertTrue(level >= 1 && level <= 30);
                }
            }
        }
    }
    @Test void everyEliteAndExtremeLevelRespectsHardStatCaps() {
        for (int level : new int[]{Integer.MIN_VALUE, 1, 2, 10, 20, 30, Integer.MAX_VALUE}) {
            for (int variant = 0; variant <= 3; variant++) {
                var stats = MobProgressionPolicy.stats(level, variant);
                assertTrue(stats.health() >= 1 && stats.health() <= 2.5);
                assertTrue(stats.damage() >= 1 && stats.damage() <= 1.5);
                assertTrue(stats.speed() >= 1 && stats.speed() <= 1.10);
            }
        }
        assertEquals(new MobProgressionPolicy.Stats(1, 1, 1), MobProgressionPolicy.stats(1, 0));
    }
    @Test void variantsOnlyIncreaseTheirOwnStat() {
        var normal = MobProgressionPolicy.stats(20, 0);
        var tank = MobProgressionPolicy.stats(20, 1);
        var fury = MobProgressionPolicy.stats(20, 2);
        var swift = MobProgressionPolicy.stats(20, 3);
        assertTrue(tank.health() > normal.health()); assertEquals(normal.damage(), tank.damage());
        assertTrue(fury.damage() > normal.damage()); assertEquals(normal.speed(), fury.speed());
        assertTrue(swift.speed() > normal.speed()); assertEquals(normal.health(), swift.health());
    }
    @Test void farmAndConversionReasonsAreNotNatural() {
        for (String reason : new String[]{"SPAWNER", "TRIAL_SPAWNER", "EGG", "SPAWNER_EGG", "COMMAND", "CUSTOM", "SLIME_SPLIT", "DROWNED", "REINFORCEMENTS", "INFECTION"})
            assertFalse(MobProgressionPolicy.natural(reason), reason);
        assertTrue(MobProgressionPolicy.natural("NATURAL"));
        assertTrue(MobProgressionPolicy.natural("PATROL"));
        assertTrue(MobProgressionPolicy.natural("CHUNK_GEN"));
        assertFalse(MobProgressionPolicy.natural(null));
    }
    @Test void playersPetsNpcsAndVanillaBossesAreExcluded() {
        assertFalse(MobProgression.hostile(null));
        assertFalse(MobProgression.hostile(entity(false, Player.class)));
        assertFalse(MobProgression.hostile(entity(false, Wolf.class)));
        assertFalse(MobProgression.hostile(entity(false, Monster.class, Tameable.class)));
        assertFalse(MobProgression.hostile(entity(false, Monster.class, Boss.class)));
        assertFalse(MobProgression.hostile(entity(false, Warden.class)));
        assertFalse(MobProgression.hostile(entity(false, ElderGuardian.class)));
        assertFalse(MobProgression.hostile(entity(true, Monster.class)));
        assertTrue(MobProgression.hostile(entity(false, Monster.class)));
        assertTrue(MobProgression.hostile(entity(false, Slime.class)));
    }
    private LivingEntity entity(boolean npc, Class<?>... types) {
        return (LivingEntity) Proxy.newProxyInstance(getClass().getClassLoader(), types,
                (proxy, method, args) -> method.getName().equals("hasMetadata") ? npc : null);
    }
    @Test void sourceKeepsFinalSpawnGuardsAndNeverRenamesOrInflatesRewards() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/MobProgression.java"));
        assertTrue(source.contains("generation != expected || event.isCancelled()"));
        assertTrue(source.contains("pending >= 4096"));
        assertTrue(source.contains("!mob.getPersistentDataContainer().getKeys().isEmpty()"));
        assertTrue(source.contains("instance.removeModifier(key)"));
        assertFalse(source.contains(".customName(" + "plugin"));
        assertFalse(source.contains("setCustomName"));
        assertFalse(source.contains("setDroppedExp")); assertFalse(source.contains("handleDeath("));
        assertFalse(source.contains("dropItem")); assertFalse(source.contains("award("));
        assertTrue(source.contains("mob.getPersistentDataContainer().remove(active)"));
        assertFalse(source.contains("mob.getPersistentDataContainer().remove(level)"));
    }
}

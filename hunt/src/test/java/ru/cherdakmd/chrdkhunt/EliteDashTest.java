package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EliteDashTest {
    @TempDir Path dir;
    private EliteDash.Settings defaults() { return EliteDash.Settings.bounded(true, 20, 12, 1.10); }
    @Test void defaultsAreShortAndCooldownIsMuchLonger() {
        var s = defaults();
        assertTrue(s.enabled()); assertEquals(20, s.ticks()); assertEquals(12000, s.cooldown()); assertEquals(1.10, s.ceiling());
    }
    @Test void hostileConfigurationCannotRemoveHardCaps() {
        var high = EliteDash.Settings.bounded(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 1e99);
        assertEquals(30, high.ticks()); assertEquals(60000, high.cooldown()); assertEquals(1.10, high.ceiling());
        var low = EliteDash.Settings.bounded(false, Integer.MIN_VALUE, Integer.MIN_VALUE, -1e99);
        assertFalse(low.enabled()); assertEquals(10, low.ticks()); assertEquals(10000, low.cooldown()); assertEquals(1.01, low.ceiling());
    }
    @Test void nonFiniteConfigurationCannotReachAttribute() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(1.10, EliteDash.Settings.bounded(true, 20, 12, bad).ceiling());
    }
    @Test void everyLevelAndCeilingRespectsCombinedChrdkCapWithoutCompoundingTenPercent() {
        for (int level = 1; level <= 30; level++) for (int i = 1010; i <= 1100; i++) {
            var s = EliteDash.Settings.bounded(true, 20, 12, i / 1000.0);
            double normal = MobProgressionPolicy.stats(level, EliteDash.VARIANT).speed(), boost = s.boost(normal);
            assertTrue(Double.isFinite(boost) && boost >= 0);
            assertTrue(normal * (1 + boost) <= 1.10, "level=" + level + ", ceiling=" + s.ceiling());
            if (normal < s.ceiling()) assertEquals(s.ceiling(), normal * (1 + boost), 1e-14);
            else assertEquals(0, boost);
        }
        assertTrue(defaults().boost(1.058) < .04);
        assertEquals(.10, defaults().boost(1), 1e-14);
    }
    @Test void invalidOrAlreadyAtCapLevelNeverGetsAnExtraModifier() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0, .999, 1.10, 2})
            assertEquals(0, defaults().boost(value));
    }
    @Test void onlyExpectedOwnedLevelModifierIsAccepted() {
        assertTrue(EliteDash.ownedLevel(.058, 1.058, true));
        assertFalse(EliteDash.ownedLevel(.10, 1.058, true));
        assertFalse(EliteDash.ownedLevel(.058, 1.058, false));
        assertFalse(EliteDash.ownedLevel(Double.NaN, 1.058, true));
        assertFalse(EliteDash.ownedLevel(.058, Double.NaN, true));
        assertFalse(EliteDash.ownedLevel(-.01, .99, true));
    }
    @Test void onlyExplicitGroundTypesCanEnterDashPool() {
        assertTrue(EliteDash.supported("ZOMBIE")); assertTrue(EliteDash.supported("SKELETON"));
        assertTrue(EliteDash.supported("DROWNED"));
        for (String type : List.of("CREEPER", "GHAST", "PHANTOM", "BLAZE", "VEX", "BREEZE", "GUARDIAN", "WITHER", "WARDEN", "WOLF", "PLAYER", "UNKNOWN"))
            assertFalse(EliteDash.supported(type), type);
        assertFalse(EliteDash.supported(null));
    }
    @Test void approachRangeExcludesMeleeAndDistantTargetsAndNonFiniteValues() {
        assertTrue(EliteDash.range(4)); assertTrue(EliteDash.range(144));
        for (double value : new double[]{0, -1, 3.999, 144.001, Double.NaN, Double.POSITIVE_INFINITY}) assertFalse(EliteDash.range(value));
    }
    @Test void cooldownSurvivesRoundTripAndClockRollbackCannotRefreshIt() {
        Long reserved = defaults().reserve(0, 1000);
        assertEquals(Long.valueOf(13000), reserved);
        long loaded = Long.parseLong(reserved.toString());
        assertNull(defaults().reserve(loaded, 12999)); assertNull(defaults().reserve(loaded, 500));
        assertEquals(Long.valueOf(25000), defaults().reserve(loaded, 13000));
    }
    @Test void corruptCooldownDisabledAbilityAndOverflowAreFailClosed() {
        assertNull(defaults().reserve(-1, 1000)); assertNull(defaults().reserve(0, -1));
        assertNull(defaults().reserve(0, Long.MAX_VALUE)); assertNull(defaults().reserve(Long.MAX_VALUE, 1000));
        assertNull(EliteDash.Settings.bounded(false, 20, 12, 1.10).reserve(0, 1000));
        assertEquals(Long.valueOf(Long.MAX_VALUE), defaults().reserve(0, Long.MAX_VALUE - 12000));
    }
    @Test void allEightToggleCombinationsHaveStableIdsAndOneSharedPool() {
        for (boolean armor : new boolean[]{false, true}) for (boolean recovery : new boolean[]{false, true}) for (boolean dash : new boolean[]{false, true}) {
            Set<Integer> expected = new HashSet<>(Set.of(1, 2, 3));
            if (armor) expected.add(4); if (recovery) expected.add(5); if (dash) expected.add(6);
            Set<Integer> actual = new HashSet<>(); int count = MobProgressionPolicy.variantCount(armor, recovery, dash);
            for (int i = 0; i < count; i++) actual.add(MobProgressionPolicy.variant(i, armor, recovery, dash));
            assertEquals(expected, actual); assertEquals(expected.size(), count);
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(-1, armor, recovery, dash));
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(count, armor, recovery, dash));
        }
        assertTrue(MobProgressionPolicy.valid(30, 6)); assertFalse(MobProgressionPolicy.valid(30, 7));
        assertEquals(MobProgressionPolicy.stats(30, 0), MobProgressionPolicy.stats(30, 6));
    }
    @Test void burstCannotBeStackedExtendedOrReplaceItsOwner() {
        var windows = new BurstWindows<String>(); UUID mob = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(windows.start(mob, a, "first", 100, 20));
        assertFalse(windows.start(mob, b, "second", 110, 30));
        assertEquals(a, windows.get(mob).owner()); assertEquals("first", windows.get(mob).value()); assertEquals(120, windows.get(mob).until());
    }
    @Test void expiryAndOwnerCleanupReturnOnlyAffectedEffects() {
        var windows = new BurstWindows<String>(); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), mob = UUID.randomUUID();
        windows.start(mob, a, "a", 100, 20); windows.start(UUID.randomUUID(), b, "b", 105, 20);
        assertTrue(windows.drain(e -> e.until() <= 119).isEmpty());
        assertEquals(List.of("a"), windows.drain(e -> e.until() <= 120).stream().map(BurstWindows.Entry::value).toList());
        assertEquals(1, windows.size()); assertTrue(windows.drain(e -> e.owner().equals(a)).isEmpty());
        assertEquals(List.of("b"), windows.drain(e -> e.owner().equals(b)).stream().map(BurstWindows.Entry::value).toList());
        assertEquals(0, windows.size());
    }
    @Test void activeBudgetIsStrictAndDoesNotEvictAnExistingEffect() {
        var windows = new BurstWindows<Integer>(); UUID owner = UUID.randomUUID();
        for (int i = 0; i < BurstWindows.LIMIT; i++) assertTrue(windows.start(new UUID(0, i), owner, i, 0, 20));
        assertFalse(windows.start(UUID.randomUUID(), owner, 999, 0, 20)); assertEquals(128, windows.size());
        assertEquals(0, windows.remove(new UUID(0, 0)).value());
        assertTrue(windows.start(UUID.randomUUID(), owner, 999, 0, 20));
        assertEquals(128, windows.clear().size()); assertEquals(0, windows.size()); assertTrue(windows.clear().isEmpty());
    }
    @Test void invalidWindowsNeverConsumeCapacityAndSnapshotsAreImmutable() {
        var windows = new BurstWindows<String>(); UUID mob = UUID.randomUUID(), owner = UUID.randomUUID();
        assertFalse(windows.start(null, owner, "x", 0, 20)); assertFalse(windows.start(mob, null, "x", 0, 20));
        assertFalse(windows.start(mob, owner, null, 0, 20)); assertFalse(windows.start(mob, owner, "x", -1, 20));
        assertFalse(windows.start(mob, owner, "x", 0, 0)); assertFalse(windows.start(mob, owner, "x", Long.MAX_VALUE, 20));
        assertEquals(0, windows.size()); assertTrue(windows.start(mob, owner, "x", 0, 20));
        assertThrows(UnsupportedOperationException.class, () -> windows.snapshot().clear());
        assertNotNull(windows.remove(mob)); assertNull(windows.remove(mob));
    }
    @Test void newVariantRoundTripsWithoutKillsMoneyOrNewLootRules() {
        UUID id = UUID.randomUUID(); var store = new HuntStore(dir, null);
        assertTrue(store.observeMob(id, "Hunter", 20, 6)); store.saveNow();
        var loaded = new HuntStore(dir, null); loaded.load(); assertEquals(Set.of(6), loaded.observedElites(id));
        assertEquals(0, loaded.coins(id)); assertEquals(0, loaded.player(id).killsTotal());
        var entry = new HuntHistory.Entry(UUID.randomUUID(), 1000, "ZOMBIE", 20, 6, "world");
        assertEquals(List.of(entry), HuntHistory.read(HuntHistory.write(List.of(entry))));
    }
    @Test void nativeTransientAttributeOnlyAndCooldownIsWrittenBeforeApplication() throws Exception {
        String source = source("EliteDashing");
        assertTrue(source.contains("attribute.addTransientModifier("));
        assertTrue(source.indexOf(".set(cooldown, PersistentDataType.LONG, reserved)") < source.indexOf("attribute.addTransientModifier("));
        assertTrue(source.contains("bursts.start(") && source.indexOf("bursts.start(") < source.indexOf("attribute.addTransientModifier("));
        for (String forbidden : List.of(".setVelocity(", ".teleport(", ".setTarget(", ".setAI(", ".setBaseValue(", ".addModifier(", ".setDamage(", ".setHealth(", "award(", "getWorlds(", "getLivingEntities(")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("getModifier(normal)")); assertTrue(source.contains("levelMatches(attribute, levelFactor)"));
        assertTrue(source.contains("data.has(cooldown, PersistentDataType.LONG)")); assertFalse(source.contains(".remove(cooldown)"));
    }
    @Test void finalSignalsAndEveryTickEligibilityAreRevalidated() throws Exception {
        String source = source("EliteDashing");
        for (String guard : List.of("!event.isCancelled()", "mob.getLastDamageCause() == event", "EliteRecovery.lostHealth",
                "expected != generation", "requests.remove(id, ticket)", "!player.equals(mob.getTarget())", "!mob.isOnGround()",
                "mob.isInWater()", "mob.isInLava()", "mob.isInsideVehicle()", "!mob.getPassengers().isEmpty()", "!mob.hasLineOfSight(player)",
                "GameMode.SURVIVAL", "!plugin.coreApi().isAuthenticated(player)", "player.hasMetadata(\"NPC\")", "chrdkhunt.use",
                "EliteDash.range", "view.elite() == EliteDash.VARIANT", "tick < entry.until()", "eligible(context.player()",
                "runTaskTimer(plugin, this::sweep, 1L, 1L)")) assertTrue(source.contains(guard), guard);
    }
    @Test void lifecycleAndAtomicReloadClearTransientButKeepCooldown() throws Exception {
        String source = source("EliteDashing"), progression = source("MobProgression"), plugin = source("HuntPlugin");
        for (String event : List.of("PlayerQuitEvent", "PlayerTeleportEvent", "EntitiesUnloadEvent", "EntityTeleportEvent", "EntityDeathEvent", "EntityTransformEvent"))
            assertTrue(source.contains(event), event);
        assertTrue(source.contains("generation++; confirmations.clear(); requests.clear()"));
        assertTrue(source.contains("bursts.clear()")); assertTrue(source.contains("clockTask.cancel()")); assertTrue(source.contains("confirmations.stop()"));
        assertTrue(progression.contains("private void refresh(LivingEntity mob) {\n        EliteDashing.clearModifier(mob, plugin);"));
        assertTrue(progression.contains("private void strip(LivingEntity mob) {\n        EliteDashing.clearModifier(mob, plugin);"));
        assertTrue(plugin.indexOf("settings = candidate") < plugin.indexOf("dashing.reload()"));
        assertTrue(plugin.contains("if (dashing != null) dashing.stop()"));
    }
    private static String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/" + name + ".java")); }
}

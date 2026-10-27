package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EliteRecoveryTest {
    @TempDir Path dir;
    private EliteRecovery.Settings defaults() { return EliteRecovery.Settings.bounded(true, 2, 10, 3, 3, 8); }

    @Test void defaultsAreSmallDelayedAndLifetimeBounded() {
        var s = defaults();
        assertTrue(s.enabled()); assertEquals(40, s.delayTicks()); assertEquals(10000, s.cooldownMillis());
        assertEquals(3, s.attempts()); assertEquals(3, s.hp()); assertEquals(.08, s.fraction());
    }
    @Test void hostileConfigurationCannotRemoveHardCaps() {
        var s = EliteRecovery.Settings.bounded(true, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 1e100, 1e100);
        assertEquals(100, s.delayTicks()); assertEquals(10000, s.cooldownMillis());
        assertEquals(3, s.attempts()); assertEquals(3, s.hp()); assertEquals(.08, s.fraction());
        var low = EliteRecovery.Settings.bounded(false, -1, 999, -1, -10, -10);
        assertFalse(low.enabled()); assertEquals(20, low.delayTicks()); assertEquals(60000, low.cooldownMillis());
        assertEquals(1, low.attempts()); assertEquals(.5, low.hp()); assertEquals(.01, low.fraction());
    }
    @Test void nonFiniteConfigurationUsesFiniteDefaults() {
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            var s = EliteRecovery.Settings.bounded(true, 2, 10, 3, value, value);
            assertEquals(3, s.hp()); assertEquals(.08, s.fraction());
        }
    }
    @Test void amountRespectsHpFractionAndMissingHealthSimultaneously() {
        var s = defaults();
        assertEquals(1.6, s.amount(10, 20), 1e-9);
        assertEquals(3, s.amount(50, 100), 1e-9);
        assertEquals(.25, s.amount(19.75, 20), 1e-9);
        assertEquals(0, s.amount(20, 20)); assertEquals(0, s.amount(21, 20));
    }
    @Test void invalidOrDeadHealthCannotResurrectOrProduceNan() {
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1, 0}) {
            assertEquals(0, defaults().amount(value, 20)); assertEquals(0, defaults().amount(10, value));
        }
    }
    @Test void stampAllowsThreeAttemptsButNeverRefillsOnRoundTrip() {
        var state = EliteRecovery.Stamp.decode(null);
        for (int i = 0; i < 3; i++) {
            assertTrue(state.ready(defaults(), i * 10000L));
            state = state.spend(defaults(), i * 10000L);
            assertNotNull(state); assertEquals(i + 1, state.used());
            state = EliteRecovery.Stamp.decode(state.encode());
        }
        assertFalse(state.ready(defaults(), 999999)); assertNull(state.spend(defaults(), 999999));
    }
    @Test void cooldownClockRollbackAndOverflowAreFailClosed() {
        var state = new EliteRecovery.Stamp(1, 20000);
        assertFalse(state.ready(defaults(), 19999)); assertTrue(state.ready(defaults(), 20000));
        assertFalse(state.ready(defaults(), -1)); assertFalse(state.ready(defaults(), 100));
        assertNull(new EliteRecovery.Stamp(0, 0).spend(defaults(), Long.MAX_VALUE));
        assertEquals(30000, state.spend(defaults(), 20000).next());
    }
    @Test void malformedStoredBudgetsDoNotBecomeFreshCharges() {
        for (long[] data : new long[][]{{}, {1}, {1, 2, 3}, {-1, 0}, {4, 0}, {0, -1}, {Long.MAX_VALUE, 0}})
            assertNull(EliteRecovery.Stamp.decode(data));
        assertEquals(new EliteRecovery.Stamp(0, 0), EliteRecovery.Stamp.decode(new long[]{0, 0}));
    }
    @Test void disabledOrLoweredAttemptLimitDoesNotResetStoredUsage() {
        var state = new EliteRecovery.Stamp(2, 0);
        assertFalse(state.ready(EliteRecovery.Settings.bounded(false, 2, 10, 3, 3, 8), 100));
        assertFalse(state.ready(EliteRecovery.Settings.bounded(true, 2, 10, 1, 3, 8), 100));
        assertTrue(state.ready(defaults(), 100)); assertEquals(2, state.used());
    }
    @Test void actualPositiveHealthLossAndFinalUncancelledDamageAreRequired() {
        assertTrue(EliteRecovery.lostHealth(20, 18, 2, false));
        assertFalse(EliteRecovery.lostHealth(20, 18, 2, true));
        assertFalse(EliteRecovery.lostHealth(20, 20, 2, false));
        assertFalse(EliteRecovery.lostHealth(20, 0, 20, false));
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(EliteRecovery.lostHealth(20, 18, bad, false));
    }
    @Test void allToggleCombinationsShareOneElitePoolWithoutUnsupportedIds() {
        for (boolean armor : new boolean[]{false, true}) for (boolean recovery : new boolean[]{false, true}) {
            Set<Integer> variants = new HashSet<>();
            for (int i = 0; i < MobProgressionPolicy.variantCount(armor, recovery); i++)
                variants.add(MobProgressionPolicy.variant(i, armor, recovery));
            assertEquals(armor, variants.contains(4)); assertEquals(recovery, variants.contains(5));
            assertTrue(variants.containsAll(Set.of(1, 2, 3)));
            assertEquals(3 + (armor ? 1 : 0) + (recovery ? 1 : 0), variants.size());
        }
        assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(4, false, false));
        assertEquals(MobProgressionPolicy.stats(30, 0), MobProgressionPolicy.stats(30, 5));
        assertTrue(MobProgressionPolicy.valid(30, 5)); assertFalse(MobProgressionPolicy.valid(30, ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE + 1));
    }
    @Test void replacementTicketsPreventOldCallbacksFromRemovingNewWork() {
        var windows = new RecoveryWindows(); UUID mob = UUID.randomUUID(), owner = UUID.randomUUID();
        var old = windows.replace(mob, owner); var current = windows.replace(mob, owner);
        assertEquals(1, windows.size()); assertFalse(windows.owns(mob, old)); assertFalse(windows.remove(mob, old));
        assertTrue(windows.owns(mob, current)); assertTrue(windows.remove(mob, current)); assertEquals(0, windows.size());
    }
    @Test void quittingOwnerCleansOnlyTheirWindowsAndBudgetIsBounded() {
        var windows = new RecoveryWindows(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < RecoveryWindows.LIMIT; i++) assertNotNull(windows.replace(new UUID(0, i), i == 0 ? a : b));
        assertNull(windows.replace(UUID.randomUUID(), a));
        assertNotNull(windows.replace(new UUID(0, 1), b));
        assertEquals(List.of(new UUID(0, 0)), windows.forget(a)); assertEquals(4095, windows.size());
        assertNotNull(windows.replace(UUID.randomUUID(), a)); windows.clear(); assertEquals(0, windows.size());
    }
    @Test void storeAndHistoryKeepNewEliteWithoutTouchingCoinsOrRewards() {
        UUID id = UUID.randomUUID(); var store = new HuntStore(dir, null);
        assertTrue(store.observeMob(id, "Hunter", 20, 5)); store.saveNow();
        var loaded = new HuntStore(dir, null); loaded.load(); assertEquals(Set.of(5), loaded.observedElites(id));
        assertEquals(0, loaded.coins(id)); assertEquals(0, loaded.player(id).killsTotal());
        var entry = new HuntHistory.Entry(UUID.randomUUID(), 1000, "ZOMBIE", 20, 5, "world");
        assertEquals(List.of(entry), HuntHistory.read(HuntHistory.write(List.of(entry))));
    }
    @Test void listenerUsesNativeHealingAndRevalidatesFinalDamageWithoutChangingLoot() throws Exception {
        String source = source("EliteRegeneration");
        for (String guard : List.of("event.isCancelled()", "mob.getLastDamageCause() != event", "EliteRecovery.lostHealth",
                "GameMode.SURVIVAL", "!plugin.coreApi().isAuthenticated(player)", "expected != generation",
                "view.elite() == EliteRecovery.VARIANT", "distance > 32 * 32", "delayed.contains(id, HEAL)"))
            assertTrue(source.contains(guard), guard);
        assertTrue(source.contains("mob.heal(amount, EntityRegainHealthEvent.RegainReason.CUSTOM)"));
        assertTrue(source.indexOf("spent.encode()") < source.indexOf("mob.heal("));
        assertFalse(source.contains("setHealth(")); assertFalse(source.contains("setDamage("));
        assertFalse(source.contains("setDroppedExp(")); assertFalse(source.contains("dropItem("));
        assertFalse(source.contains("addItem(")); assertFalse(source.contains("award("));
    }
    @Test void lifecycleAndReloadCancelTimersButNeverDeletePersistentBudget() throws Exception {
        String source = source("EliteRegeneration");
        for (String event : List.of("PlayerQuitEvent", "PlayerTeleportEvent", "EntitiesUnloadEvent", "EntityTeleportEvent", "EntityDeathEvent", "EntityTransformEvent"))
            assertTrue(source.contains(event));
        assertTrue(source.contains("confirmations.clear(); delayed.clear(); windows.clear()"));
        assertTrue(source.contains("confirmations.stop(); delayed.stop(); windows.clear()"));
        assertFalse(source.contains(".remove(budget)"));
        String plugin = source("HuntPlugin");
        assertTrue(plugin.contains("if (regeneration != null) regeneration.stop()"));
        assertTrue(plugin.contains("() -> { if (regeneration != null) regeneration.reload(); }"));
        String books = Files.readString(Path.of("../enchants/src/main/java/ru/cherdakmd/chrdkenchants/MobBookListener.java"));
        assertTrue(books.contains("variant > MobProgressionKeys.MAX_ELITE"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/" + name + ".java"));
    }
}

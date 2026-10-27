package ru.cherdakmd.chrdkhunt;

import com.google.common.base.Function;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EliteTacticsTest {
    @TempDir Path dir;
    private EliteTactics.Settings defaults() { return EliteTactics.Settings.bounded(true, 60, 12, 3, 10); }
    @Test void defaultsAreOneShortWindowSmallReductionAndThreeReactions() {
        var s = defaults();
        assertTrue(s.enabled()); assertEquals(60, s.ticks()); assertEquals(12000, s.cooldown());
        assertEquals(3, s.attempts()); assertEquals(.10, s.fraction()); assertEquals(9, s.reduced(10));
    }
    @Test void extremeConfigurationCannotCreateImmunityOrUnlimitedBudget() {
        var high = EliteTactics.Settings.bounded(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1e100);
        assertEquals(100, high.ticks()); assertEquals(60000, high.cooldown()); assertEquals(3, high.attempts()); assertEquals(.15, high.fraction());
        var low = EliteTactics.Settings.bounded(false, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, -1e100);
        assertFalse(low.enabled()); assertEquals(40, low.ticks()); assertEquals(10000, low.cooldown()); assertEquals(1, low.attempts()); assertEquals(.05, low.fraction());
        assertTrue(Double.isNaN(low.reduced(10)));
    }
    @Test void nonFinitePercentFallsBackAndNonFiniteDamageCannotEnterEvent() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(.10, EliteTactics.Settings.bounded(true, 60, 12, 3, bad).fraction());
            assertTrue(Double.isNaN(defaults().reduced(bad)));
        }
        for (double bad : new double[]{0, -1, Double.MIN_VALUE}) assertTrue(Double.isNaN(defaults().reduced(bad)));
    }
    @Test void finiteDamageMatrixRemainsPositiveAndNoMoreThanFifteenPercentIsRemovedFromBase() {
        for (double damage : new double[]{1e-100, .01, 1, 10, 100, 1e100, Double.MAX_VALUE}) for (int percent = 5; percent <= 15; percent++) {
            double result = EliteTactics.Settings.bounded(true, 60, 12, 3, percent).reduced(damage);
            assertTrue(Double.isFinite(result) && result > 0 && result < damage);
            assertEquals(1 - percent / 100.0, result / damage, 1e-14);
        }
    }
    @Test void onlyPlayerMeleeSweepAndPlayerProjectileAreRecognized() {
        assertEquals(EliteTactics.Kind.MELEE, EliteTactics.kind("ENTITY_ATTACK", true, false));
        assertEquals(EliteTactics.Kind.MELEE, EliteTactics.kind("ENTITY_SWEEP_ATTACK", true, false));
        assertEquals(EliteTactics.Kind.PROJECTILE, EliteTactics.kind("PROJECTILE", false, true));
        for (String cause : List.of("MAGIC", "CUSTOM", "FIRE", "FIRE_TICK", "ENTITY_EXPLOSION", "FALL", "THORNS", "POISON"))
            assertNull(EliteTactics.kind(cause, true, true));
        assertNull(EliteTactics.kind("ENTITY_ATTACK", false, true));
        assertNull(EliteTactics.kind("PROJECTILE", true, false)); assertNull(EliteTactics.kind(null, true, true));
    }
    @Test void preparedTypeAndExactOwnerMustBothMatch() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (var prepared : EliteTactics.Kind.values()) for (var incoming : EliteTactics.Kind.values()) {
            assertEquals(prepared == incoming, EliteTactics.matches(prepared, incoming, a, a));
            assertFalse(EliteTactics.matches(prepared, incoming, a, b));
        }
        assertFalse(EliteTactics.matches(null, null, a, a));
        assertFalse(EliteTactics.matches(EliteTactics.Kind.MELEE, EliteTactics.Kind.MELEE, null, a));
        assertFalse(EliteTactics.matches(EliteTactics.Kind.MELEE, EliteTactics.Kind.MELEE, a, null));
    }
    @Test void persistentBudgetAllowsOnlyThreeReactionsAcrossRoundTrips() {
        var state = EliteTactics.Stamp.decode(null);
        for (int i = 0; i < 3; i++) {
            assertTrue(state.ready(defaults(), i * 12000L));
            state = state.spend(defaults(), i * 12000L); assertNotNull(state); assertEquals(i + 1, state.used());
            state = EliteTactics.Stamp.decode(state.encode());
        }
        assertFalse(state.ready(defaults(), 999999)); assertNull(state.spend(defaults(), 999999));
    }
    @Test void cooldownRollbackAndOverflowNeverProduceNewCharges() {
        var state = new EliteTactics.Stamp(1, 20000);
        assertFalse(state.ready(defaults(), 19999)); assertFalse(state.ready(defaults(), -1));
        assertTrue(state.ready(defaults(), 20000)); assertEquals(32000, state.spend(defaults(), 20000).next());
        assertNull(state.spend(defaults(), Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, new EliteTactics.Stamp(0, 0).spend(defaults(), Long.MAX_VALUE - 12000).next());
    }
    @Test void malformedPdcCannotRefillBudget() {
        for (long[] data : new long[][]{{}, {1}, {1, 2, 3}, {-1, 0}, {4, 0}, {0, -1}, {Long.MAX_VALUE, 0}})
            assertNull(EliteTactics.Stamp.decode(data));
        assertEquals(new EliteTactics.Stamp(0, 0), EliteTactics.Stamp.decode(new long[]{0, 0}));
        assertFalse(new EliteTactics.Stamp(-1, 0).ready(defaults(), 0));
    }
    @Test void disablingOrLoweringBudgetDoesNotRestoreAlreadySpentReactions() {
        var used = new EliteTactics.Stamp(2, 0);
        assertFalse(used.ready(EliteTactics.Settings.bounded(false, 60, 12, 3, 10), 1000));
        assertFalse(used.ready(EliteTactics.Settings.bounded(true, 60, 12, 1, 10), 1000));
        assertEquals(2, used.used()); assertTrue(used.ready(defaults(), 1000));
    }
    @Test void allSixteenToggleCombinationsPreserveIdsAndOneSharedPool() {
        for (int mask = 0; mask < 16; mask++) {
            boolean armor = (mask & 1) != 0, recovery = (mask & 2) != 0, dash = (mask & 4) != 0, tactics = (mask & 8) != 0;
            Set<Integer> expected = new HashSet<>(Set.of(1, 2, 3)), actual = new HashSet<>();
            if (armor) expected.add(4); if (recovery) expected.add(5); if (dash) expected.add(6); if (tactics) expected.add(7);
            int count = MobProgressionPolicy.variantCount(armor, recovery, dash, tactics);
            for (int i = 0; i < count; i++) actual.add(MobProgressionPolicy.variant(i, armor, recovery, dash, tactics));
            assertEquals(expected, actual); assertEquals(expected.size(), count);
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(-1, armor, recovery, dash, tactics));
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(count, armor, recovery, dash, tactics));
        }
        assertTrue(MobProgressionPolicy.valid(30, 7)); assertFalse(MobProgressionPolicy.valid(30, ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE + 1));
        assertEquals(MobProgressionPolicy.stats(30, 0), MobProgressionPolicy.stats(30, 7));
    }
    @Test void preparationDoesNotExtendWindowOrChangeTypeOrOwner() {
        var windows = new BurstWindows<EliteTactics.Kind>(); UUID mob = UUID.randomUUID(), owner = UUID.randomUUID();
        assertTrue(windows.start(mob, owner, EliteTactics.Kind.MELEE, 10, 60));
        assertFalse(windows.start(mob, UUID.randomUUID(), EliteTactics.Kind.PROJECTILE, 30, 60));
        assertEquals(70, windows.get(mob).until()); assertEquals(owner, windows.get(mob).owner());
        assertEquals(EliteTactics.Kind.MELEE, windows.get(mob).value());
        assertTrue(windows.drain(e -> e.until() <= 69).isEmpty());
        assertEquals(1, windows.drain(e -> e.until() <= 70).size()); assertNull(windows.remove(mob));
    }
    @Test void boundedPreparationDoesNotEvictOtherPlayersAndCleanupIsOwnerScoped() {
        var windows = new BurstWindows<String>(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < BurstWindows.LIMIT; i++) assertTrue(windows.start(new UUID(0, i), i == 0 ? a : b, "ready", 0, 60));
        assertFalse(windows.start(UUID.randomUUID(), a, "overflow", 0, 60));
        assertEquals(1, windows.drain(e -> e.owner().equals(a)).size()); assertEquals(127, windows.size());
        assertEquals(127, windows.clear().size()); assertEquals(0, windows.size());
    }
    @Test void consumedWindowIsOneShotAndLateCancellationCannotRefundPersistentStamp() {
        var windows = new BurstWindows<String>(); UUID mob = UUID.randomUUID(), owner = UUID.randomUUID();
        windows.start(mob, owner, "ready", 0, 60);
        var spent = EliteTactics.Stamp.decode(null).spend(defaults(), 1000);
        assertNotNull(windows.remove(mob)); assertNull(windows.remove(mob));
        var loaded = EliteTactics.Stamp.decode(spent.encode());
        assertEquals(1, loaded.used()); assertFalse(loaded.ready(defaults(), 1000));
    }
    @Test void newVariantObservationAndHistoryDoNotAwardLootOrKills() {
        UUID id = UUID.randomUUID(); var store = new HuntStore(dir, null);
        assertTrue(store.observeMob(id, "Hunter", 20, 7)); store.saveNow();
        var loaded = new HuntStore(dir, null); loaded.load(); assertEquals(Set.of(7), loaded.observedElites(id));
        assertEquals(0, loaded.coins(id)); assertEquals(0, loaded.player(id).killsTotal());
        var entry = new HuntHistory.Entry(UUID.randomUUID(), 1000, "ZOMBIE", 20, 7, "world");
        assertEquals(List.of(entry), HuntHistory.read(HuntHistory.write(List.of(entry))));
    }
    @Test void bukkitSetDamageRecalculatesNativeArmorAndAbsorptionWithoutManualHealthChanges() {
        var event = damageEvent(-2);
        assertEquals(5, event.getFinalDamage());
        event.setDamage(defaults().reduced(event.getDamage()));
        assertEquals(7.2, event.getDamage(), 1e-12); assertEquals(-1.8, event.getDamage(EntityDamageEvent.DamageModifier.ARMOR), 1e-12);
        assertEquals(-1, event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
        assertEquals(4.4, event.getFinalDamage(), 1e-12); assertEquals(8, event.getOriginalDamage(EntityDamageEvent.DamageModifier.BASE));
        assertFalse(event.isCancelled());
    }
    @Test void bukkitRecalculationPreservesForeignModifierDeltaAndCancellation() {
        var event = damageEvent(-3); event.setCancelled(true);
        event.setDamage(defaults().reduced(event.getDamage()));
        assertEquals(-2.8, event.getDamage(EntityDamageEvent.DamageModifier.ARMOR), 1e-12);
        assertEquals(3.4, event.getFinalDamage(), 1e-12); assertTrue(event.isCancelled());
    }
    @Test void sourcePreparesOnlyAfterFinalUncancelledActualHealthLoss() throws Exception {
        String source = source("EliteAdaptation");
        for (String guard : List.of("EventPriority.MONITOR, ignoreCancelled = true", "requests.remove(id, ticket)", "expected != generation",
                "mob.getLastDamageCause() != event", "EliteRecovery.lostHealth", "event.isCancelled()", "GameMode.SURVIVAL", "mob instanceof Player",
                "!plugin.coreApi().isAuthenticated(player)", "player.hasMetadata(\"NPC\")", "chrdkhunt.use", "view.elite() != EliteTactics.VARIANT",
                "distance <= 32 * 32", "mob.hasLineOfSight(player)", "armed.contains(id)", "current.ready(settings")) assertTrue(source.contains(guard), guard);
        int monitor = source.indexOf("public void hit(");
        assertFalse(source.substring(monitor).contains("event.setDamage("));
    }
    @Test void sourceSpendsBeforeOneShotDamageChangeAndNeverCreatesImmunityOrRewards() throws Exception {
        String source = source("EliteAdaptation");
        assertTrue(source.contains("EventPriority.HIGHEST, ignoreCancelled = true"));
        assertTrue(source.contains("EliteTactics.matches(context.kind(), kind, entry.owner(), player.getUniqueId())"));
        assertTrue(source.indexOf("spent.encode()") < source.indexOf("event.setDamage(reduced)"));
        assertTrue(source.indexOf("forget(entry.id());\n        event.setDamage(reduced)") > 0);
        for (String forbidden : List.of(".setCancelled(", ".setHealth(", ".setVelocity(", ".setTarget(", ".teleport(", "addPotionEffect(", "award(", "setDroppedExp(", "getWorlds(", "getLivingEntities(", ".remove(budget)"))
            assertFalse(source.contains(forbidden), forbidden);
        assertTrue(source.contains("data.has(budget, PersistentDataType.LONG_ARRAY)"));
    }
    @Test void lifecycleAndAtomicReloadClearWindowsButNeverPersistentBudget() throws Exception {
        String source = source("EliteAdaptation"), plugin = source("HuntPlugin");
        for (String event : List.of("PlayerQuitEvent", "PlayerTeleportEvent", "EntitiesUnloadEvent", "EntityTeleportEvent", "EntityDeathEvent", "EntityTransformEvent")) assertTrue(source.contains(event), event);
        assertTrue(source.contains("generation++; confirmations.clear(); requests.clear(); armed.clear()"));
        assertTrue(source.contains("clockTask.cancel()")); assertTrue(source.contains("confirmations.stop()"));
        assertTrue(source.contains("tick >= entry.until()")); assertTrue(source.contains("armed.drain(entry -> entry.owner().equals(owner))"));
        assertTrue(plugin.indexOf("settings = candidate") < plugin.indexOf("adaptation.reload()"));
        assertTrue(plugin.contains("if (adaptation != null) adaptation.stop()"));
    }
    private static String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/" + name + ".java")); }
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> null));
    }
    private static EntityDamageEvent damageEvent(double armor) {
        var values = new EnumMap<EntityDamageEvent.DamageModifier, Double>(EntityDamageEvent.DamageModifier.class);
        var functions = new EnumMap<EntityDamageEvent.DamageModifier, Function<? super Double, Double>>(EntityDamageEvent.DamageModifier.class);
        values.put(EntityDamageEvent.DamageModifier.BASE, 8.0); values.put(EntityDamageEvent.DamageModifier.ARMOR, armor); values.put(EntityDamageEvent.DamageModifier.ABSORPTION, -1.0);
        functions.put(EntityDamageEvent.DamageModifier.BASE, damage -> 0.0);
        functions.put(EntityDamageEvent.DamageModifier.ARMOR, damage -> -.25 * damage);
        functions.put(EntityDamageEvent.DamageModifier.ABSORPTION, damage -> -Math.min(1.0, Math.max(0, damage)));
        return new EntityDamageEvent(stub(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, stub(DamageSource.class), values, functions);
    }
}

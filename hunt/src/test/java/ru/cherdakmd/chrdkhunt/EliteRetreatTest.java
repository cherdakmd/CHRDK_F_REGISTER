package ru.cherdakmd.chrdkhunt;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class EliteRetreatTest {
    @TempDir Path dir;
    private EliteRetreat.Settings defaults() { return EliteRetreat.Settings.bounded(true, 30, 15, 2, 25, 4); }
    @Test void defaultsAreBriefSmallAndLifetimeBounded() {
        var s = defaults(); assertTrue(s.enabled()); assertEquals(30, s.ticks()); assertEquals(15000, s.cooldown());
        assertEquals(2, s.attempts()); assertEquals(.25, s.threshold()); assertEquals(4, s.distance());
        assertEquals(16, EliteRetreat.ACTIVE_LIMIT); assertEquals(4, EliteRetreat.PLAN_LIMIT); assertEquals(8, EliteRetreat.SEARCH_LIMIT);
    }
    @Test void hostileConfigurationCannotRemoveHardCaps() {
        var high = EliteRetreat.Settings.bounded(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1e99, 1e99);
        assertEquals(40, high.ticks()); assertEquals(60000, high.cooldown()); assertEquals(2, high.attempts()); assertEquals(.30, high.threshold()); assertEquals(5, high.distance());
        var low = EliteRetreat.Settings.bounded(false, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, -1e99, -1e99);
        assertFalse(low.enabled()); assertEquals(20, low.ticks()); assertEquals(10000, low.cooldown()); assertEquals(1, low.attempts()); assertEquals(.15, low.threshold()); assertEquals(3, low.distance());
    }
    @Test void nonFiniteSettingsUseFiniteDefaults() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var s = EliteRetreat.Settings.bounded(true, 30, 15, 2, value, value);
            assertEquals(.25, s.threshold()); assertEquals(4, s.distance());
        }
    }
    @Test void onlyLivingLowHealthCanRetreatAndHealingEndsEligibility() {
        assertTrue(defaults().low(5, 20)); assertFalse(defaults().low(5.001, 20));
        assertFalse(defaults().low(0, 20)); assertFalse(defaults().low(20, 20));
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0}) {
            assertFalse(defaults().low(value, 20)); assertFalse(defaults().low(5, value));
        }
        assertFalse(EliteRetreat.Settings.bounded(false, 30, 15, 2, 25, 4).low(1, 20));
    }
    @Test void twoAttemptsRemainSpentAcrossPdcRoundTripsEvenWhenNoRouteExists() {
        var state = EliteRetreat.Stamp.decode(null);
        for (int i = 0; i < 2; i++) {
            state = state.spend(defaults(), i * 15000L); assertNotNull(state); assertEquals(i + 1, state.used());
            state = EliteRetreat.Stamp.decode(state.encode());
        }
        assertFalse(state.ready(defaults(), 999999)); assertNull(state.spend(defaults(), 999999));
    }
    @Test void malformedBudgetIsNotAFreeNewAttempt() {
        for (long[] data : new long[][]{{}, {1}, {1, 2, 3}, {-1, 0}, {3, 0}, {0, -1}, {Long.MAX_VALUE, 0}}) assertNull(EliteRetreat.Stamp.decode(data));
        assertEquals(new EliteRetreat.Stamp(0, 0), EliteRetreat.Stamp.decode(new long[]{0, 0}));
        assertFalse(new EliteRetreat.Stamp(-1, 0).ready(defaults(), 0));
    }
    @Test void cooldownRollbackOverflowAndLoweredLimitCannotRefillBudget() {
        var state = new EliteRetreat.Stamp(1, 20000);
        assertFalse(state.ready(defaults(), 19999)); assertFalse(state.ready(defaults(), -1));
        assertEquals(35000, state.spend(defaults(), 20000).next()); assertNull(state.spend(defaults(), Long.MAX_VALUE));
        assertFalse(state.ready(EliteRetreat.Settings.bounded(true, 30, 15, 1, 25, 4), 20000));
        assertEquals(Long.MAX_VALUE, new EliteRetreat.Stamp(0, 0).spend(defaults(), Long.MAX_VALUE - 15000).next());
    }
    @Test void onlySupportedGroundGoalSelectorTypesEnterRetreatPool() {
        for (String type : List.of("ZOMBIE", "SKELETON", "WITHER_SKELETON", "WITCH", "ZOMBIFIED_PIGLIN")) assertTrue(EliteRetreat.supported(type));
        for (String type : List.of("CREEPER", "PHANTOM", "GHAST", "DROWNED", "SPIDER", "RAVAGER", "PIGLIN", "PIGLIN_BRUTE", "BREEZE", "WARDEN", "WITHER", "WOLF", "PLAYER")) assertFalse(EliteRetreat.supported(type), type);
        assertFalse(EliteRetreat.supported(null));
    }
    @Test void threeCandidateDirectionsAreAwayAndNeverFartherThanConfiguredStep() {
        for (double px : new double[]{-10, 0, 10}) for (double pz : new double[]{-10, 0, 10}) {
            if (px == 0 && pz == 0) continue;
            var points = EliteRetreat.candidates(0, 0, px, pz, 4); assertEquals(3, points.size());
            for (var point : points) {
                assertEquals(4, Math.hypot(point.x(), point.z()), 1e-12);
                assertTrue(Math.hypot(point.x() - px, point.z() - pz) > Math.hypot(px, pz));
            }
        }
    }
    @Test void overlappingOrNonFinitePositionsDoNotProduceRandomEscape() {
        assertTrue(EliteRetreat.candidates(0, 0, 0, 0, 4).isEmpty());
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertTrue(EliteRetreat.candidates(value, 0, 1, 0, 4).isEmpty());
            assertTrue(EliteRetreat.candidates(0, 0, value, 0, 4).isEmpty());
            assertTrue(EliteRetreat.candidates(0, 0, 1, 0, value).isEmpty());
        }
        assertTrue(EliteRetreat.candidates(0, 0, 1, 0, 100).isEmpty());
    }
    @Test void cellsCannotCrossChunkHeightOrLocalRadiusIncludingNegativeCoordinates() {
        assertTrue(EliteRetreat.cell(10, 64, 10, 8, 64, 8));
        assertFalse(EliteRetreat.cell(16, 64, 8, 14, 64, 8)); assertFalse(EliteRetreat.cell(8, 63, 8, 8, 64, 8));
        assertFalse(EliteRetreat.cell(15, 64, 8, 8, 64, 8));
        assertTrue(EliteRetreat.cell(-15, 64, -15, -10, 64, -10));
        assertFalse(EliteRetreat.cell(0, 64, -10, -1, 64, -10));
        assertFalse(EliteRetreat.cell(Integer.MAX_VALUE, 64, 0, Integer.MIN_VALUE, 64, 0));
    }
    @Test void hazardousOrNonFullFloorIsRejected() {
        assertTrue(EliteRetreat.floor("STONE", true)); assertFalse(EliteRetreat.floor("STONE_SLAB", false));
        for (String type : List.of("MAGMA_BLOCK", "LAVA", "WATER", "CACTUS", "CAMPFIRE", "POWDER_SNOW", "SWEET_BERRY_BUSH", "WITHER_ROSE", "FIRE")) assertFalse(EliteRetreat.floor(type, true));
        assertFalse(EliteRetreat.floor(null, true));
    }
    @Test void thirtyTwoPoolCombinationsPreserveIdsAndDoNotAddASecondEliteRoll() {
        for (int mask = 0; mask < 32; mask++) {
            boolean armor = (mask & 1) != 0, recovery = (mask & 2) != 0, dash = (mask & 4) != 0, tactics = (mask & 8) != 0, retreat = (mask & 16) != 0;
            Set<Integer> expected = new HashSet<>(Set.of(1, 2, 3)), actual = new HashSet<>();
            for (int flag = 0; flag < 5; flag++) if ((mask & 1 << flag) != 0) expected.add(flag + 4);
            int count = MobProgressionPolicy.variantCount(armor, recovery, dash, tactics, retreat);
            for (int draw = 0; draw < count; draw++) actual.add(MobProgressionPolicy.variant(draw, armor, recovery, dash, tactics, retreat));
            assertEquals(expected, actual); assertEquals(expected.size(), count);
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(count, armor, recovery, dash, tactics, retreat));
            assertThrows(IllegalArgumentException.class, () -> MobProgressionPolicy.variant(-1, armor, recovery, dash, tactics, retreat));
        }
        assertTrue(MobProgressionPolicy.valid(30, 8)); assertFalse(MobProgressionPolicy.valid(30, 9));
        assertEquals(MobProgressionPolicy.stats(30, 0), MobProgressionPolicy.stats(30, 8));
    }
    @Test void newVariantHistoryAndObservationDoNotAwardCurrencyOrKills() {
        UUID id = UUID.randomUUID(); var store = new HuntStore(dir, null); assertTrue(store.observeMob(id, "Hunter", 20, 8)); store.saveNow();
        var loaded = new HuntStore(dir, null); loaded.load(); assertEquals(Set.of(8), loaded.observedElites(id));
        assertEquals(0, loaded.coins(id)); assertEquals(0, loaded.player(id).killsTotal());
        var entry = new HuntHistory.Entry(UUID.randomUUID(), 1000, "ZOMBIE", 20, 8, "world");
        assertEquals(List.of(entry), HuntHistory.read(HuntHistory.write(List.of(entry))));
    }
    @Test void actualGoalOnlyControlsMoveAndStartsNativePathAtNormalSpeedOnce() {
        var r = new Rig(); assertEquals(EnumSet.of(GoalType.MOVE), r.goal.getTypes());
        r.goal.getTypes().clear(); assertEquals(EnumSet.of(GoalType.MOVE), r.goal.getTypes());
        assertTrue(r.goal.shouldActivate()); r.goal.start(); r.goal.start();
        assertEquals(1, r.moves); assertEquals(1.0, r.speed); assertTrue(r.goal.shouldStayActive()); assertFalse(r.goal.shouldActivate());
    }
    @Test void actualGoalDoesNotStartAfterPermissionContextWasInvalidated() {
        var r = new Rig(); r.valid.set(false); assertFalse(r.goal.shouldActivate()); r.goal.start();
        assertEquals(0, r.moves); assertTrue(r.goal.finished); assertFalse(r.goal.shouldStayActive());
    }
    @Test void actualGoalStopIsIdempotentAndDoesNotRestartOrRestoreAnOldPath() {
        var r = new Rig(); r.goal.start(); r.goal.stop(); r.goal.stop(); r.goal.start();
        assertEquals(1, r.moves); assertEquals(1, r.stops); assertNull(r.current); assertTrue(r.goal.finished);
    }
    @Test void actualGoalYieldsToForeignPathWithoutStoppingIt() {
        var r = new Rig(); r.goal.start(); var foreign = result(List.of(new Location(null, 9, 64, 9)));
        r.current = foreign; assertFalse(r.goal.shouldStayActive()); r.goal.stop();
        assertEquals(0, r.stops); assertSame(foreign, r.current);
    }
    @Test void mutationOfNativePathInvalidatesApprovalButStillStopsItsOwnNavigation() {
        var r = new Rig(); r.goal.start(); r.points.getLast().setX(3);
        assertFalse(r.goal.routeUnchanged()); assertTrue(r.goal.ownsPath()); assertFalse(r.goal.shouldStayActive());
        r.goal.stop(); assertEquals(1, r.stops); assertNull(r.current);
    }
    @Test void actualGoalNeverStopsForeignNavigationBeforeItsOwnStart() {
        var r = new Rig(); var foreign = result(List.of(new Location(null, 9, 64, 9))); r.current = foreign;
        r.goal.stop(); assertEquals(0, r.stops); assertSame(foreign, r.current);
    }
    @Test void actualGoalStopsContinuingAfterNativePathCompletesOrContextExpires() {
        var r = new Rig(); r.goal.start(); r.current = null; assertFalse(r.goal.shouldStayActive());
        var other = new Rig(); other.goal.start(); other.valid.set(false); assertFalse(other.goal.shouldStayActive());
    }
    @Test void nativeMoveRefusalAndValidityExceptionFailClosed() {
        var r = new Rig(); r.moveResult = false; r.goal.start(); assertTrue(r.goal.finished); assertFalse(r.goal.shouldStayActive());
        var broken = new Rig(); broken.broken = true; assertFalse(broken.goal.shouldActivate()); assertEquals(1, broken.faults);
        assertFalse(broken.goal.shouldActivate()); assertEquals(1, broken.faults); assertEquals(0, broken.moves);
    }
    @Test void expiredOwnerWindowsAndOldCallbacksCannotTransferRetreatToAnotherPlayer() {
        var requests = new RecoveryWindows(); var windows = new BurstWindows<String>(); UUID mob = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var old = requests.replace(mob, a); var current = requests.replace(mob, b);
        assertFalse(requests.remove(mob, old)); assertTrue(requests.owns(mob, current));
        assertTrue(windows.start(mob, b, "goal", 0, 30)); assertFalse(windows.start(mob, a, "other", 10, 40));
        assertTrue(windows.drain(e -> e.owner().equals(a)).isEmpty());
        assertEquals(1, windows.drain(e -> e.until() <= 30).size()); assertEquals(0, windows.size());
    }
    @Test void sourceConfirmsRealFinalDamageAndWaitsForKnockbackBeforeGroundNavigation() throws Exception {
        String source = source("EliteFleeing");
        for (String guard : List.of("EventPriority.MONITOR, ignoreCancelled = true", "EliteRecovery.lostHealth", "event.isCancelled()", "mob.getLastDamageCause() != event",
                "requests.owns(id, ticket)", "expected != generation", "runTaskLater(plugin, action, 10L)", "!low(mob)", "!mob.isOnGround()",
                "player.equals(mob.getTarget())", "GameMode.SURVIVAL", "!plugin.coreApi().isAuthenticated(player)", "chrdkhunt.use", "player.hasMetadata(\"NPC\")",
                "view.elite() != EliteRetreat.VARIANT", "mob.hasLineOfSight(player)", "mob.isInWater()", "mob.isInLava()", "!mob.getPassengers().isEmpty()")) assertTrue(source.contains(guard), guard);
    }
    @Test void sourceValidatesRouteAndReentrantCallbacksBeforeAddingGoalAndKeepsHardBudgets() throws Exception {
        String source = source("EliteFleeing");
        assertTrue(source.indexOf("spent.encode()") < source.indexOf(".findPath(destination)"));
        int path = source.indexOf(".findPath(destination)"), added = source.indexOf(".addGoal(mob, 0, goal.navigation)");
        assertTrue(path < source.indexOf("!requests.owns(mob.getUniqueId(), ticket)") && path < added);
        for (String guard : List.of("active.size() >= EliteRetreat.ACTIVE_LIMIT", "plans >= EliteRetreat.PLAN_LIMIT", "searches >= EliteRetreat.SEARCH_LIMIT",
                "points.size() > EliteRetreat.MAX_POINTS", "!world.isChunkLoaded", "EliteRetreat.cell", "!safePath(points, origin)", "!path.canReachFinalPoint()",
                "!world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()", "floor.isOccluding()", "y + 2 >= world.getMaxHeight()")) assertTrue(source.contains(guard), guard);
    }
    @Test void sourceNeverTeleportsChangesBlocksTargetsSpeedAttributesOrRewards() throws Exception {
        String source = source("EliteFleeing") + source("RetreatNavigation");
        for (String forbidden : List.of(".teleport(", ".setVelocity(", ".setTarget(", ".setAI(", ".setHealth(", ".setDamage(", ".setType(", ".breakNaturally(",
                ".removeAllGoals(", ".setCanOpenDoors(", ".setCanFloat(", ".setBaseValue(", ".addModifier(", ".loadChunk(", ".getChunkAt(", "getWorlds(", "getLivingEntities(", "award(", ".remove(budget)")) assertFalse(source.contains(forbidden), forbidden);
        assertTrue(source.contains("moveTo(path, 1.0)")); assertTrue(source.contains("removeGoal(goal.mob, goal.navigation)"));
    }
    @Test void sourceClearsGoalsAndQueuesAcrossLifecycleOnlyAfterSuccessfulReloadCommit() throws Exception {
        String source = source("EliteFleeing"), plugin = source("HuntPlugin");
        for (String event : List.of("PlayerQuitEvent", "PlayerTeleportEvent", "EntitiesUnloadEvent", "EntityTeleportEvent", "EntityDeathEvent", "EntityTransformEvent", "EntityTargetLivingEntityEvent")) assertTrue(source.contains(event), event);
        assertTrue(source.contains("confirmations.clear(); settling.clear(); requests.clear()"));
        assertTrue(source.contains("clockTask.cancel()")); assertTrue(source.contains("confirmations.stop(); settling.stop()"));
        assertTrue(source.contains("entry.owner().equals(owner)")); assertTrue(source.contains("generation++"));
        assertTrue(plugin.indexOf("settings = candidate") < plugin.indexOf("fleeing.reload()")); assertTrue(plugin.contains("if (fleeing != null) fleeing.stop()"));
    }
    private static String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/" + name + ".java")); }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Pathfinder.PathResult result(List<Location> points) {
        return proxy(Pathfinder.PathResult.class, (self, method, args) -> switch (method.getName()) {
            case "getPoints" -> points; case "getFinalPoint", "getNextPoint" -> points.getLast();
            case "canReachFinalPoint" -> true; case "getNextPointIndex" -> 0; default -> null;
        });
    }
    private static final class Rig {
        final AtomicBoolean valid = new AtomicBoolean(true); boolean broken, moveResult = true;
        int moves, stops, faults; double speed; Pathfinder.PathResult current;
        final List<Location> points = List.of(new Location(null, 1, 64, 1), new Location(null, 2, 64, 1));
        final Pathfinder.PathResult path = result(points);
        final Pathfinder navigation = proxy(Pathfinder.class, (self, method, args) -> switch (method.getName()) {
            case "moveTo" -> { moves++; speed = (double) args[1]; if (moveResult) current = (Pathfinder.PathResult) args[0]; yield moveResult; }
            case "getCurrentPath" -> current; case "stopPathfinding" -> { stops++; current = null; yield null; } default -> null;
        });
        final Mob mob = proxy(Mob.class, (self, method, args) -> method.getName().equals("getPathfinder") ? navigation : null);
        final RetreatNavigation goal = new RetreatNavigation(mob, GoalKey.of(Mob.class, new NamespacedKey("chrdk_hunt", "test_retreat")), path, points,
                () -> { if (broken) throw new IllegalStateException(); return valid.get(); }, failure -> faults++);
    }
}

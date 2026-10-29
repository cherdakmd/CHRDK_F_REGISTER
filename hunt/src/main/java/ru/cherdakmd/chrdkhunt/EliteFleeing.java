package ru.cherdakmd.chrdkhunt;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.GoalKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Временный MOVE-goal: чужие/ванильные goals и target не удаляются и не подменяются. */
final class EliteFleeing implements Listener {
    private static final String CONFIRM = "retreat-confirm", SETTLE = "retreat-settle";
    private final HuntPlugin plugin;
    private final NamespacedKey budget;
    private final GoalKey<Mob> goalKey;
    private final MobTaskQueue confirmations, settling;
    private final RecoveryWindows requests = new RecoveryWindows();
    private final BurstWindows<RetreatGoal> active = new BurstWindows<>();
    private final BukkitTask clockTask;
    private EliteRetreat.Settings settings;
    private boolean stopped;
    private long generation, tick;
    private int searches, plans;

    EliteFleeing(HuntPlugin plugin) {
        this.plugin = plugin;
        budget = new NamespacedKey(plugin, "elite_retreat_v1");
        goalKey = GoalKey.of(Mob.class, new NamespacedKey(plugin, "mob_retreat"));
        confirmations = new MobTaskQueue(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
        settling = new MobTaskQueue(action -> { var task = Bukkit.getScheduler().runTaskLater(plugin, action, 10L); return task::cancel; });
        reload();
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 1L, 1L);
    }
    void reload() {
        clear(); var config = plugin.getConfig(); String path = "mob-progression.retreat.";
        settings = EliteRetreat.Settings.bounded(config.getBoolean(path + "enabled", true), config.getInt(path + "duration-ticks", 30),
                config.getInt(path + "cooldown-seconds", 15), config.getInt(path + "max-attempts", 2),
                config.getDouble(path + "health-percent", 25), config.getDouble(path + "distance", 4));
    }
    private EliteRetreat.Stamp stamp(Mob mob) {
        var data = mob.getPersistentDataContainer();
        if (!data.has(budget)) return EliteRetreat.Stamp.decode(null);
        return data.has(budget, PersistentDataType.LONG_ARRAY) ? EliteRetreat.Stamp.decode(data.get(budget, PersistentDataType.LONG_ARRAY)) : null;
    }
    private boolean low(Mob mob) {
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        return health != null && settings.low(mob.getHealth(), health.getValue());
    }
    private boolean eligible(Player player, Mob mob, UUID world, boolean target, boolean ground, int range) {
        if (stopped || !settings.enabled() || !plugin.isEnabled() || !mob.isValid() || mob.isDead() || mob.isInvulnerable()
                || !EliteRetreat.supported(mob.getType().name()) || mob.isInWater() || mob.isInLava()
                || mob.isInsideVehicle() || !mob.getPassengers().isEmpty() || ground && !mob.isOnGround()) return false;
        var progression = plugin.progression(); var view = progression == null ? null : progression.view(mob);
        if (view == null || view.elite() != EliteRetreat.VARIANT) return false;
        if (!player.isOnline() || player.isDead() || player.isInvulnerable() || player.hasMetadata("NPC")
                || player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission("chrdkhunt.use")
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                || !world.equals(player.getWorld().getUID()) || !world.equals(mob.getWorld().getUID())
                || target && !player.equals(mob.getTarget())) return false;
        double distance = player.getLocation().distanceSquared(mob.getLocation());
        return Double.isFinite(distance) && distance >= 0 && distance <= range * range && mob.hasLineOfSight(player);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null || !(event.getEntity() instanceof Mob mob) || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;
        UUID id = mob.getUniqueId(), world = mob.getWorld().getUID();
        if (active.contains(id) || active.size() >= EliteRetreat.ACTIVE_LIMIT || settling.contains(id, SETTLE)
                || !eligible(player, mob, world, false, false, 16)) return;
        var state = stamp(mob);
        if (state == null || !state.ready(settings, System.currentTimeMillis())) return;
        var ticket = requests.replace(id, player.getUniqueId());
        if (ticket == null) return;
        confirmations.cancel(id, CONFIRM);
        double before = mob.getHealth(); long expected = generation;
        try {
            if (!confirmations.submit(id, CONFIRM, () -> {
                boolean scheduled = false;
                try {
                    if (!requests.owns(id, ticket) || expected != generation || !eligible(player, mob, world, true, false, 16)
                            || mob.getLastDamageCause() != event || !EliteRecovery.lostHealth(before, mob.getHealth(), event.getFinalDamage(), event.isCancelled()) || !low(mob)) return;
                    scheduled = settling.submit(id, SETTLE, () -> {
                        try { if (requests.owns(id, ticket) && expected == generation) begin(player, mob, world, ticket, expected); }
                        finally { requests.remove(id, ticket); }
                    });
                } finally { if (!scheduled && requests.owns(id, ticket)) forget(id); }
            })) forget(id);
        } catch (RuntimeException | Error failure) { forget(id); throw failure; }
    }
    private boolean safeCell(Location point, Location origin) {
        var world = origin.getWorld(); int x = point.getBlockX(), y = point.getBlockY(), z = point.getBlockZ();
        if (!world.equals(point.getWorld()) || !EliteRetreat.cell(x, y, z, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ())
                || !world.isChunkLoaded(x >> 4, z >> 4) || y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return false;
        // Полоса 3x3: только ровный твёрдый пол и три блока воздуха, без дверей, жидкостей и края чанка.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((x + dx >> 4) != (origin.getBlockX() >> 4) || (z + dz >> 4) != (origin.getBlockZ() >> 4)) return false;
            var floor = world.getBlockAt(x + dx, y - 1, z + dz).getType();
            if (!EliteRetreat.floor(floor.name(), floor.isOccluding())) return false;
            for (int dy = 0; dy < 3; dy++) if (!world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()) return false;
        }
        return true;
    }
    private boolean safePath(List<Location> points, Location origin) {
        if (points.isEmpty() || points.size() > EliteRetreat.MAX_POINTS) return false;
        for (var point : points) if (!safeCell(point, origin)) return false;
        return true;
    }
    private void begin(Player player, Mob mob, UUID world, RecoveryWindows.Ticket ticket, long expected) {
        if (active.size() >= EliteRetreat.ACTIVE_LIMIT || plans >= EliteRetreat.PLAN_LIMIT || searches >= EliteRetreat.SEARCH_LIMIT) return;
        plans++;
        if (!eligible(player, mob, world, true, true, 16) || !low(mob)) return;
        var origin = mob.getLocation().clone();
        if (Math.abs(origin.getY() - origin.getBlockY()) > .05 || !safeCell(origin, origin)) return;
        var candidates = EliteRetreat.candidates(origin.getX(), origin.getZ(), player.getLocation().getX(), player.getLocation().getZ(), settings.distance());
        if (candidates.isEmpty()) return;
        var state = stamp(mob); var spent = state == null ? null : state.spend(settings, System.currentTimeMillis());
        if (spent == null) return;
        // Даже заблокированный поиск расходует попытку: недоступный маршрут нельзя бесконечно пересчитывать ударами.
        mob.getPersistentDataContainer().set(budget, PersistentDataType.LONG_ARRAY, spent.encode());
        for (var candidate : candidates) {
            var destination = new Location(mob.getWorld(), Math.floor(candidate.x()) + .5, origin.getBlockY(), Math.floor(candidate.z()) + .5);
            if (!safeCell(destination, origin) || !away(origin, destination, player.getLocation(), 1)) continue;
            if (searches >= EliteRetreat.SEARCH_LIMIT) break;
            searches++;
            var path = mob.getPathfinder().findPath(destination);
            // findPath может вызвать cancellable Paper-событие с чужими callbacks (reload/TP/quit).
            if (!requests.owns(mob.getUniqueId(), ticket) || expected != generation
                    || !eligible(player, mob, world, true, true, 16) || !low(mob)
                    || mob.getLocation().distanceSquared(origin) > .25) return;
            if (path == null || !path.canReachFinalPoint() || path.getFinalPoint() == null) continue;
            var points = List.copyOf(path.getPoints());
            if (!safePath(points, origin)) continue;
            var end = path.getFinalPoint().clone().add(.5, 0, .5);
            if (!away(origin, end, player.getLocation(), 1)) continue;
            var goal = new RetreatGoal(mob, player, world, origin, end, path, points);
            if (!active.start(mob.getUniqueId(), player.getUniqueId(), goal, tick, settings.ticks())) return;
            try { Bukkit.getMobGoals().addGoal(mob, 0, goal.navigation); }
            catch (RuntimeException | Error failure) { forget(mob.getUniqueId()); throw failure; }
            return;
        }
    }
    private static boolean away(Location from, Location to, Location player, double gain) {
        double before = Math.hypot(from.getX() - player.getX(), from.getZ() - player.getZ());
        double after = Math.hypot(to.getX() - player.getX(), to.getZ() - player.getZ());
        return Double.isFinite(before) && Double.isFinite(after) && after >= before + gain;
    }
    private final class RetreatGoal {
        final Mob mob; final Player player; final UUID world; final Location origin, destination;
        final List<Location> points; final RetreatNavigation navigation;
        RetreatGoal(Mob mob, Player player, UUID world, Location origin, Location destination, Pathfinder.PathResult path, List<Location> points) {
            this.mob = mob; this.player = player; this.world = world; this.origin = origin; this.destination = destination; this.points = points;
            navigation = new RetreatNavigation(mob, goalKey, path, points, this::valid,
                    failure -> plugin.getLogger().warning("AI отступления элиты завершён (" + failure.getClass().getSimpleName() + ")."));
        }
        boolean ownsEntry() { var entry = active.get(mob.getUniqueId()); return entry != null && entry.value() == this && tick < entry.until(); }
        boolean valid() {
            return !navigation.finished && navigation.routeUnchanged() && ownsEntry() && eligible(player, mob, world, true, true, 24) && low(mob)
                    && safeCell(mob.getLocation(), origin) && safePath(points, origin) && away(mob.getLocation(), destination, player.getLocation(), 0);
        }
    }
    private void sweep() {
        tick++; searches = 0; plans = 0;
        for (var entry : active.snapshot()) {
            try { var goal = entry.value(); if (!goal.valid() || goal.navigation.started && !goal.navigation.ownsPath()) forget(entry.id()); }
            catch (RuntimeException | LinkageError failure) {
                forget(entry.id()); plugin.getLogger().warning("Отступление элиты снято (" + failure.getClass().getSimpleName() + ").");
            }
        }
    }
    private void forget(UUID id) {
        requests.remove(id); confirmations.cancel(id, CONFIRM); settling.cancel(id, SETTLE);
        var entry = active.remove(id);
        if (entry != null) {
            var goal = entry.value();
            try { goal.navigation.stop(); } finally { Bukkit.getMobGoals().removeGoal(goal.mob, goal.navigation); }
        }
    }
    private void forgetPlayer(UUID owner) {
        for (UUID id : requests.forget(owner)) forget(id);
        for (var entry : active.snapshot()) if (entry.owner().equals(owner)) forget(entry.id());
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerTeleport(PlayerTeleportEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void unload(EntitiesUnloadEvent event) { for (var entity : event.getEntities()) forget(entity.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent event) { forget(event.getEntity().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) {
        var entry = active.get(event.getEntity().getUniqueId());
        if (entry != null && !entry.value().player.equals(event.getTarget())) forget(entry.id());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
        if (event.getEntity() instanceof Player player) forgetPlayer(player.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) {
        forget(event.getEntity().getUniqueId());
        for (var entity : event.getTransformedEntities()) forget(entity.getUniqueId());
    }
    void clear() {
        generation++; confirmations.clear(); settling.clear(); requests.clear();
        for (var entry : active.snapshot()) {
            try { forget(entry.id()); }
            catch (RuntimeException | LinkageError failure) { plugin.getLogger().warning("Снятие AI отступления прервано (" + failure.getClass().getSimpleName() + ")."); }
        }
    }
    void stop() { stopped = true; clockTask.cancel(); clear(); confirmations.stop(); settling.stop(); }
}

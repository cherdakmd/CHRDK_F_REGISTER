package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/** Подтверждённый урон подготавливает одну реакцию на тот же тип атаки того же игрока. */
final class EliteAdaptation implements Listener {
    private static final String CONFIRM = "tactics-confirm";
    private record Context(LivingEntity mob, Player player, UUID world, EliteTactics.Kind kind) { }
    private final HuntPlugin plugin;
    private final NamespacedKey budget;
    private final MobTaskQueue confirmations;
    private final RecoveryWindows requests = new RecoveryWindows();
    private final BurstWindows<Context> armed = new BurstWindows<>();
    private final BukkitTask clockTask;
    private EliteTactics.Settings settings;
    private boolean stopped;
    private long generation, tick;

    EliteAdaptation(HuntPlugin plugin) {
        this.plugin = plugin;
        budget = new NamespacedKey(plugin, "elite_tactics_v1");
        confirmations = new MobTaskQueue(action -> {
            var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel;
        });
        reload();
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 1L, 1L);
    }
    void reload() {
        clear();
        var config = plugin.getConfig(); String path = "mob-progression.tactics.";
        settings = EliteTactics.Settings.bounded(config.getBoolean(path + "enabled", true),
                config.getInt(path + "window-ticks", 60), config.getInt(path + "cooldown-seconds", 12),
                config.getInt(path + "max-reactions", 3), config.getDouble(path + "reduction-percent", 10));
    }
    private EliteTactics.Stamp stamp(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        if (!data.has(budget)) return EliteTactics.Stamp.decode(null);
        return data.has(budget, PersistentDataType.LONG_ARRAY) ? EliteTactics.Stamp.decode(data.get(budget, PersistentDataType.LONG_ARRAY)) : null;
    }
    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        return event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
    }
    private static EliteTactics.Kind kind(EntityDamageByEntityEvent event) {
        return EliteTactics.kind(event.getCause().name(), event.getDamager() instanceof Player,
                event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player);
    }
    private boolean eligible(Player player, LivingEntity mob, UUID world) {
        if (stopped || !settings.enabled() || !plugin.isEnabled() || mob instanceof Player || !mob.isValid() || mob.isDead() || mob.isInvulnerable()) return false;
        var progression = plugin.progression(); var view = progression == null ? null : progression.view(mob);
        if (view == null || view.elite() != EliteTactics.VARIANT) return false;
        if (!player.isOnline() || player.isDead() || player.isInvulnerable() || player.hasMetadata("NPC")
                || player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission("chrdkhunt.use")
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                || !world.equals(player.getWorld().getUID()) || !world.equals(mob.getWorld().getUID())) return false;
        double distance = player.getLocation().distanceSquared(mob.getLocation());
        return Double.isFinite(distance) && distance >= 0 && distance <= 32 * 32 && mob.hasLineOfSight(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void defend(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob)) return;
        var entry = armed.get(mob.getUniqueId());
        if (entry == null) return;
        var context = entry.value(); var player = attacker(event); var kind = kind(event);
        if (tick >= entry.until() || !eligible(context.player(), mob, context.world())) { forget(entry.id()); return; }
        if (player == null || !EliteTactics.matches(context.kind(), kind, entry.owner(), player.getUniqueId())
                || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;
        double reduced = settings.reduced(event.getDamage());
        var state = stamp(mob); var spent = state == null ? null : state.spend(settings, System.currentTimeMillis());
        if (spent == null) { forget(entry.id()); return; }
        if (!Double.isFinite(reduced)) return;
        // До native-модификации: поздняя отмена/перезапись урона не возвращает реакцию и cooldown.
        mob.getPersistentDataContainer().set(budget, PersistentDataType.LONG_ARRAY, spent.encode());
        forget(entry.id());
        event.setDamage(reduced); // Native armor/absorption пересчитываются Bukkit, не вручную и не через setHealth.
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = attacker(event); var kind = kind(event);
        if (player == null || kind == null || !(event.getEntity() instanceof LivingEntity mob)
                || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;
        UUID id = mob.getUniqueId(), world = mob.getWorld().getUID();
        if (armed.contains(id) || armed.size() >= BurstWindows.LIMIT || !eligible(player, mob, world)) return;
        var state = stamp(mob);
        if (state == null || !state.ready(settings, System.currentTimeMillis())) return;
        var ticket = requests.replace(id, player.getUniqueId());
        if (ticket == null) return;
        confirmations.cancel(id, CONFIRM);
        long expected = generation; double before = mob.getHealth();
        try {
            if (!confirmations.submit(id, CONFIRM, () -> {
                if (!requests.remove(id, ticket) || expected != generation || !eligible(player, mob, world)
                        || mob.getLastDamageCause() != event
                        || !EliteRecovery.lostHealth(before, mob.getHealth(), event.getFinalDamage(), event.isCancelled())) return;
                var current = stamp(mob);
                if (current != null && current.ready(settings, System.currentTimeMillis()))
                    armed.start(id, player.getUniqueId(), new Context(mob, player, world, kind), tick, settings.ticks());
            })) requests.remove(id, ticket);
        } catch (RuntimeException | Error failure) { requests.remove(id, ticket); throw failure; }
    }
    private void sweep() {
        tick++;
        for (var entry : armed.snapshot()) {
            try {
                var context = entry.value();
                if (tick >= entry.until() || !eligible(context.player(), context.mob(), context.world())) forget(entry.id());
            } catch (RuntimeException | LinkageError failure) {
                forget(entry.id());
                plugin.getLogger().warning("Реакция тактической элиты снята (" + failure.getClass().getSimpleName() + ").");
            }
        }
    }
    private void forget(UUID id) { armed.remove(id); requests.remove(id); confirmations.cancel(id, CONFIRM); }
    private void forgetPlayer(UUID owner) {
        for (UUID id : requests.forget(owner)) confirmations.cancel(id, CONFIRM);
        armed.drain(entry -> entry.owner().equals(owner));
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerTeleport(PlayerTeleportEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void unload(EntitiesUnloadEvent event) { for (var entity : event.getEntities()) forget(entity.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent event) { forget(event.getEntity().getUniqueId()); }
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
    void clear() { generation++; confirmations.clear(); requests.clear(); armed.clear(); }
    void stop() { stopped = true; clockTask.cancel(); clear(); confirmations.stop(); }
}

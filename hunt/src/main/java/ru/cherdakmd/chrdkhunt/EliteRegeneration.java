package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
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

import java.util.UUID;

/** Лечение после подтверждённого PvE-попадания, через native RegainHealthEvent, без наград/ручной выдачи. */
final class EliteRegeneration implements Listener {
    private static final String CONFIRM = "recovery-confirm", HEAL = "recovery-heal";
    private final HuntPlugin plugin;
    private final NamespacedKey budget;
    private final RecoveryWindows windows = new RecoveryWindows();
    private final MobTaskQueue confirmations, delayed;
    private EliteRecovery.Settings settings;
    private long generation;

    EliteRegeneration(HuntPlugin plugin) {
        this.plugin = plugin;
        budget = new NamespacedKey(plugin, "elite_recovery_v1");
        confirmations = new MobTaskQueue(action -> {
            var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel;
        });
        delayed = new MobTaskQueue(action -> {
            var task = Bukkit.getScheduler().runTaskLater(plugin, action, settings.delayTicks()); return task::cancel;
        });
        reload();
    }
    void reload() {
        clear();
        var config = plugin.getConfig();
        String path = "mob-progression.recovery.";
        settings = EliteRecovery.Settings.bounded(config.getBoolean(path + "enabled", true),
                config.getInt(path + "delay-seconds", 2), config.getInt(path + "cooldown-seconds", 10),
                config.getInt(path + "max-attempts", 3), config.getDouble(path + "heal-hp", 3),
                config.getDouble(path + "max-health-percent", 8));
    }
    private EliteRecovery.Stamp stamp(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        if (!data.has(budget)) return EliteRecovery.Stamp.decode(null);
        return data.has(budget, PersistentDataType.LONG_ARRAY)
                ? EliteRecovery.Stamp.decode(data.get(budget, PersistentDataType.LONG_ARRAY)) : null;
    }
    private boolean eligible(Player player, LivingEntity mob, UUID world) {
        if (!settings.enabled() || !plugin.isEnabled() || !player.isOnline() || player.isDead()
                || player.getGameMode() != GameMode.SURVIVAL || player.hasMetadata("NPC")
                || !player.hasPermission("chrdkhunt.use") || plugin.coreApi() == null
                || !plugin.coreApi().isAuthenticated(player) || !mob.isValid() || mob.isDead() || mob.isInvulnerable()
                || !world.equals(player.getWorld().getUID()) || !world.equals(mob.getWorld().getUID())) return false;
        double distance = player.getLocation().distanceSquared(mob.getLocation());
        if (!Double.isFinite(distance) || distance < 0 || distance > 32 * 32) return false;
        var progression = plugin.progression();
        var view = progression == null ? null : progression.view(mob);
        return view != null && view.elite() == EliteRecovery.VARIANT;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null || !(event.getEntity() instanceof LivingEntity mob)
                || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;
        UUID id = mob.getUniqueId(), world = mob.getWorld().getUID();
        if (!eligible(player, mob, world) || delayed.contains(id, HEAL)) return;
        var state = stamp(mob);
        if (state == null || !state.ready(settings, System.currentTimeMillis())) return;
        var ticket = windows.replace(id, player.getUniqueId());
        if (ticket == null) return;
        confirmations.cancel(id, CONFIRM);
        double before = mob.getHealth(); long expected = generation;
        try {
            if (!confirmations.submit(id, CONFIRM, () -> {
                boolean scheduled = false;
                try {
                    if (!windows.owns(id, ticket)) return;
                    if (expected != generation || !eligible(player, mob, world)
                            || mob.getLastDamageCause() != event
                            || !EliteRecovery.lostHealth(before, mob.getHealth(), event.getFinalDamage(), event.isCancelled())) return;
                    scheduled = delayed.submit(id, HEAL, () -> heal(id, ticket, player, mob, world, expected));
                } finally {
                    if (!scheduled && windows.owns(id, ticket)) forget(id);
                }
            })) forget(id);
        } catch (RuntimeException | Error failure) { forget(id); throw failure; }
    }
    private void heal(UUID id, RecoveryWindows.Ticket ticket, Player player, LivingEntity mob, UUID world, long expected) {
        if (!windows.remove(id, ticket) || expected != generation || !eligible(player, mob, world)) return;
        var attribute = mob.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double amount = settings.amount(mob.getHealth(), attribute.getValue());
        var state = stamp(mob);
        if (amount <= 0 || state == null) return;
        var spent = state.spend(settings, System.currentTimeMillis());
        if (spent == null) return;
        // Бюджет и cooldown фиксируются ДО события. Отмена лечения не создаёт бесконечные повторные попытки.
        mob.getPersistentDataContainer().set(budget, PersistentDataType.LONG_ARRAY, spent.encode());
        mob.heal(amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
    }
    private void forget(UUID id) {
        windows.remove(id); confirmations.cancel(id, CONFIRM); delayed.cancel(id, HEAL);
    }
    private void forgetPlayer(UUID owner) { for (UUID id : windows.forget(owner)) forget(id); }
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
    void clear() { generation++; confirmations.clear(); delayed.clear(); windows.clear(); }
    void stop() { generation++; confirmations.stop(); delayed.stop(); windows.clear(); }
}

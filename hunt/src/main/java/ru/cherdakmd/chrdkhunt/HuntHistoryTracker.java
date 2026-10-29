package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;

/** Независимая информационная запись после финальной смерти, без вмешательства в награды. */
final class HuntHistoryTracker implements Listener {
    private static final String KIND = "history-death";
    private final HuntPlugin plugin;
    private final NamespacedKey claimed;
    private final HistoryClaims<EntityDeathEvent> claims = new HistoryClaims<>(EntityDeathEvent::isCancelled);
    private final MobTaskQueue tasks;
    HuntHistoryTracker(HuntPlugin plugin) {
        this.plugin = plugin;
        claimed = new NamespacedKey(plugin, "history_claimed_v1");
        tasks = new MobTaskQueue(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
    }
    private boolean eligible(Player player) {
        return player != null && player.isOnline() && !player.isDead() && !player.hasMetadata("NPC")
                && player.getGameMode() == GameMode.SURVIVAL && player.hasPermission("chrdkhunt.use")
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    private boolean enabled() {
        return plugin.isEnabled() && plugin.config().enabled() && plugin.getConfig().getBoolean("mob-progression.history.enabled", true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        var mob = event.getEntity(); Player killer = mob.getKiller();
        if (!enabled() || !eligible(killer) || plugin.progression() == null
                || !killer.getWorld().getUID().equals(mob.getWorld().getUID())
                || mob.getPersistentDataContainer().has(claimed)
                || !(mob.getLastDamageCause() instanceof EntityDamageByEntityEvent damage)
                || damage.isCancelled() || !Double.isFinite(damage.getFinalDamage()) || damage.getFinalDamage() <= 0) return;
        Player attacker = damage.getDamager() instanceof Player player ? player
                : damage.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
        if (!killer.equals(attacker)) return;
        MobProgression.View view = plugin.progression().view(mob);
        if (view == null) return;
        UUID id = mob.getUniqueId(), owner = killer.getUniqueId(), world = mob.getWorld().getUID();
        String worldName = mob.getWorld().getName();
        if (worldName.isBlank() || worldName.length() > 128 || worldName.chars().anyMatch(Character::isISOControl)) return;
        var entry = new HuntHistory.Entry(id, System.currentTimeMillis(), mob.getType().name(), view.level(), view.elite(), worldName);
        if (!claims.begin(id, owner, event)) return;
        try {
            tasks.cancel(id, KIND);
            if (!tasks.submit(id, KIND, () -> {
                if (!claims.finish(id, event) || !enabled() || !mob.isDead() || !eligible(killer)
                        || !killer.getWorld().getUID().equals(world) || !mob.getWorld().getUID().equals(world)
                        || damage.isCancelled() || !Double.isFinite(damage.getFinalDamage()) || damage.getFinalDamage() <= 0
                        || mob.getLastDamageCause() != damage
                        || mob.getPersistentDataContainer().has(claimed)
                        || plugin.progression() == null || !view.equals(plugin.progression().view(mob))) return;
                if (plugin.store().recordHistory(owner, killer.getName(), entry)) {
                    mob.getPersistentDataContainer().set(claimed, PersistentDataType.BYTE, (byte) 1);
                    plugin.requestSave();
                }
            })) claims.discard(id, event);
        } catch (RuntimeException | Error failure) { claims.discard(id, event); throw failure; }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }
    void forget(UUID owner) { for (UUID victim : claims.forget(owner)) tasks.cancel(victim, KIND); }
    void clear() { try { tasks.clear(); } finally { claims.clear(); } }
    void stop() { try { tasks.stop(); } finally { claims.clear(); } }
}

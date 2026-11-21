package ru.cherdakmd.chrdkitems;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.PassivePotions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Main-thread held-item passive; effects are reconciled through Core's shared owner. */
final class MudShovel implements Listener {
    private final CustomItemsPlugin plugin;
    private final Set<UUID> active = new HashSet<>();
    private BukkitTask task;
    private long tick;
    private static final int PERIOD = 20;
    private static final List<PotionEffect> EFFECTS = List.of(
            new PotionEffect(PotionEffectType.SPEED, 1, 0, true, false, true),
            new PotionEffect(PotionEffectType.REGENERATION, 1, 0, true, false, true));

    MudShovel(CustomItemsPlugin plugin) { this.plugin = plugin; }
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }
    void stop() { if (task != null) task.cancel(); PassivePotions.cancel(plugin); active.clear(); }
    private boolean holding(Player player) {
        return plugin.isEnabled() && player.isOnline() && !player.isDead()
                && PrototypeEffects.identityMatches(player.getInventory().getItemInMainHand(), BuiltinItems.SHOVEL);
    }
    private void tick() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean held = holding(player);
            boolean was = active.contains(player.getUniqueId());
            if (held && player.hasPotionEffect(PotionEffectType.POISON)) player.removePotionEffect(PotionEffectType.POISON);
            if (held != was || (held && tick % PERIOD == 0)) {
                if (held) active.add(player.getUniqueId()); else active.remove(player.getUniqueId());
                PassivePotions.replace(plugin, "mud-shovel", player, held ? EFFECTS : List.of(), PERIOD);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void poison(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player && event.getNewEffect() != null
                && PotionEffectType.POISON.equals(event.getNewEffect().getType()) && holding(player)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.POISON
                && holding(player)) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { active.remove(event.getPlayer().getUniqueId()); }
}

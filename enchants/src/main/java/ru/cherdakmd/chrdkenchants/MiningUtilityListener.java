package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Защита перед добычей и пассивный environmental FREEZE; не боевые PvP-эффекты. */
final class MiningUtilityListener implements Listener {
    private final EnchantsPlugin plugin;
    private final Map<UUID, Integer> notified = new HashMap<>();
    MiningUtilityListener(EnchantsPlugin plugin) { this.plugin = plugin; }
    void clear() { notified.clear(); }
    @EventHandler public void quit(PlayerQuitEvent event) { notified.remove(event.getPlayer().getUniqueId()); }
    private boolean eligible(Player player) {
        return plugin.getConfig().getBoolean("utility-effects.enabled", true) && player.isOnline() && !player.isDead()
                && player.getGameMode() == GameMode.SURVIVAL && player.hasPermission("chrdkenchants.use")
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    private int level(ItemStack item, UtilityEnchant enchant) {
        if (item == null || !plugin.config().isEnabled(enchant.id)
                || !EnchantRegistry.byId(enchant.id).supports(item.getType().name())) return 0;
        int value = plugin.items().read(item).getOrDefault(enchant.id, 0);
        return value <= 0 ? 0 : enchant.level(value);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void safeguard(BlockBreakEvent event) {
        Player player = event.getPlayer(); if (!eligible(player)) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (level(tool, UtilityEnchant.TOOL_SAFEGUARD) == 0 || !(tool.getItemMeta() instanceof Damageable damage)
                || damage.isUnbreakable()) return;
        int maximum = damage.hasMaxDamage() ? damage.getMaxDamage() : tool.getType().getMaxDurability();
        if (!UtilityEnchant.unsafe(maximum, damage.getDamage())) return;
        event.setCancelled(true); // До добычи, XP и износа. Никакого ремонта или возврата предметов.
        int tick = Bukkit.getCurrentTick(); Integer previous = notified.get(player.getUniqueId());
        if (previous != null && tick - previous >= 0 && tick - previous < 60) return;
        if (notified.size() >= 4096 && previous == null) return;
        notified.put(player.getUniqueId(), tick);
        plugin.messages().send(player, "tool-safeguard-stop");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void freeze(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event instanceof EntityDamageByEntityEvent
                || event.getCause() != EntityDamageEvent.DamageCause.FREEZE || !eligible(player)
                || event.getDamageSource().getCausingEntity() != null || event.getDamageSource().getDirectEntity() != null
                || player.getLocation().getBlock().getType() != Material.POWDER_SNOW
                || !Double.isFinite(event.getDamage()) || event.getDamage() <= 0) return;
        int level = level(player.getInventory().getBoots(), UtilityEnchant.FROST_RESISTANCE);
        if (level > 0) event.setDamage(event.getDamage() * (1 - UtilityEnchant.FROST_RESISTANCE.freezePercent(level) / 100.0));
    }
}

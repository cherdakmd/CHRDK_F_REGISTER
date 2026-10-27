package ru.cherdakmd.chrdkenchants;

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/** Без временного атрибута: меняем только конкретное разрешённое PvE-knockback событие. */
final class AnchorListener implements Listener {
    private final EnchantsPlugin plugin;
    private final NamespacedKey ready;
    AnchorListener(EnchantsPlugin plugin) { this.plugin = plugin; ready = new NamespacedKey(plugin, EnchantTimers.PVE); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void knockback(EntityKnockbackByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getHitBy() instanceof LivingEntity attacker)
                || !PveEffectsListener.hostile(attacker) || !attacker.isValid() || attacker.isDead()
                || !attacker.getWorld().equals(player.getWorld())
                || event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK && event.getCause() != EntityKnockbackEvent.Cause.SWEEP_ATTACK
                || !player.isOnline() || player.isDead() || player.hasMetadata("NPC") || player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("chrdkenchants.use") || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                || !plugin.config().isEnabled("pve_anchor") || !plugin.pve().anchorPreferred(player)) return;
        if (!(player.getLastDamageCause() instanceof EntityDamageByEntityEvent damage) || !attacker.equals(damage.getDamager())
                || damage.isCancelled() || !Double.isFinite(damage.getFinalDamage()) || damage.getFinalDamage() <= 0) return;
        var boots = player.getInventory().getBoots();
        if (boots == null || !EnchantRegistry.byId("pve_anchor").supports(boots.getType().name())) return;
        var enchants = plugin.items().validated(boots);
        int level = enchants == null ? 0 : enchants.getOrDefault("pve_anchor", 0);
        if (level <= 0 || level > 2) return;
        var vector = event.getKnockback();
        if (!AnchorPolicy.finite(vector.getX(), vector.getY(), vector.getZ())) return;
        var data = player.getPersistentDataContainer();
        if (data.has(ready) && !data.has(ready, PersistentDataType.LONG)) return;
        long now = System.currentTimeMillis(), until = data.getOrDefault(ready, PersistentDataType.LONG, 0L);
        if (until < 0 || now < 0 || until > now || now > Long.MAX_VALUE - 15000) return;
        vector.setX(vector.getX() * AnchorPolicy.factor(level)); vector.setZ(vector.getZ() * AnchorPolicy.factor(level));
        data.set(ready, PersistentDataType.LONG, now + 15000); // Поздняя отмена не возвращает cooldown.
        event.setKnockback(vector);
    }
}

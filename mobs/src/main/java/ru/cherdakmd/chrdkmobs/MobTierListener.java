package ru.cherdakmd.chrdkmobs;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

/**
 * Тиры мобов на живых событиях. Естественные враги получают тир при
 * появлении (дальше от спавна мира — сильнее), убийство моба тира 2+
 * награждает игрока деньгами в {@code /bal}. Спавнеры, приручённые,
 * призванные и событийные мобы не тируются и не награждаются; повторная
 * выплата за того же моба невозможна (ключ награды — UUID моба).
 */
final class MobTierListener implements Listener {

    private final MobsPlugin plugin;
    private final NamespacedKey tierKey;

    MobTierListener(MobsPlugin plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "tier-v1");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        MobsConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy mob) || mob instanceof EnderDragon || mob instanceof Wither) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.PATROL) {
            return;
        }
        if (MobRewardPolicy.temporary(mob)) {
            return;
        }
        int tier = MobTierMath.tierFor(distanceFromSpawn(mob), config.thresholds());
        if (tier < 2) {
            return;
        }
        mob.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue()
                    * MobTierMath.healthFactor(tier, config.healthPerTier()));
            mob.setHealth(health.getValue());
        }
        var damage = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue()
                    * MobTierMath.damageFactor(tier, config.damagePerTier()));
        }
        if (config.namePrefix() && plugin.messages() != null) {
            mob.customName(plugin.messages().get("mob-name-prefix",
                    "tier", tier, "name", mob.getName()));
            mob.setCustomNameVisible(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        MobsConfig config = plugin.config();
        if (config == null || !config.enabled() || plugin.loot() == null) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy mob)) {
            return;
        }
        Integer tier = mob.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        if (tier == null || tier < 2 || MobRewardPolicy.temporary(mob)) {
            return;
        }
        Player killer = mob.getKiller();
        if (killer == null || !honest(killer)) {
            return;
        }
        long reward = MobTierMath.rewardDiamonds(tier, config.rewardPerTier());
        if (reward <= 0L) {
            return;
        }
        if (plugin.loot().accept(killer, "mob-tier-" + mob.getUniqueId(), reward)
                && plugin.messages() != null) {
            killer.sendActionBar(plugin.messages().get("mob-reward",
                    "tier", tier, "mob", mob.getName(), "amount", reward));
        }
    }

    private boolean honest(Player player) {
        return player.isOnline() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }

    private static double distanceFromSpawn(org.bukkit.entity.Entity entity) {
        var location = entity.getLocation();
        var spawn = location.getWorld() == null ? null : location.getWorld().getSpawnLocation();
        if (spawn == null) {
            return 0.0;
        }
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}

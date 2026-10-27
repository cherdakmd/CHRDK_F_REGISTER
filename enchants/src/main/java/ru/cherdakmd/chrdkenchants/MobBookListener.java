package ru.cherdakmd.chrdkenchants;

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
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.MobProgressionKeys;

/** Единственный книжный бросок на природного моба Hunt; книга входит в обычный дроп, не выдаётся в инвентарь. */
final class MobBookListener implements Listener {
    private final EnchantsPlugin plugin;
    private final NamespacedKey level = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.LEVEL);
    private final NamespacedKey elite = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ELITE);
    private final NamespacedKey origin = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ORIGIN);
    private final NamespacedKey active = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ACTIVE);
    private final NamespacedKey claimed;
    MobBookListener(EnchantsPlugin plugin) { this.plugin = plugin; claimed = new NamespacedKey(plugin, "mob_book_claimed"); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        if (!plugin.getConfig().getBoolean("sources.mobs.enabled", true)
                || !Bukkit.getPluginManager().isPluginEnabled("CHRDK_Hunt")) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || !killer.hasPermission("chrdkenchants.use")
                || !Boolean.TRUE.equals(event.getEntity().getWorld().getGameRuleValue(org.bukkit.GameRule.DO_MOB_LOOT))
                || killer.getGameMode() != GameMode.SURVIVAL || killer.isDead()
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(killer)
                || !(event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damage)) return;
        Player attacker = damage.getDamager() instanceof Player player ? player
                : damage.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
        if (!killer.equals(attacker)) return; // падение, лава, питомцы и старый combat credit не дают книгу
        var data = event.getEntity().getPersistentDataContainer();
        int rank = data.getOrDefault(level, PersistentDataType.INTEGER, 0);
        int variant = data.getOrDefault(elite, PersistentDataType.INTEGER, 0);
        if (event.getEntity().hasMetadata("NPC") || event.getEntity().customName() != null
                || event.getEntity() instanceof org.bukkit.entity.Tameable || event.getEntity() instanceof org.bukkit.entity.Boss
                || data.getOrDefault(active, PersistentDataType.BYTE, (byte) 0) != 1
                || event.getEntity().getEntitySpawnReason() == null
                || !java.util.Set.of("NATURAL", "CHUNK_GEN", "PATROL").contains(event.getEntity().getEntitySpawnReason().name())
                || rank < 1 || rank > 30 || variant < 0 || variant > 3 || data.has(claimed)
                || !MobProgressionKeys.NATURAL.equals(data.get(origin, PersistentDataType.STRING))) return;
        data.set(claimed, PersistentDataType.BYTE, (byte) 1);
        double chance = chance(variant != 0 ? "elite" : "regular", variant != 0 ? 8 : .35, variant != 0 ? 25 : 1);
        if (plugin.service().random().nextDouble() * 100 >= chance) return;
        int tier = rank >= 20 ? 3 : rank >= 10 ? 2 : 1;
        var roll = plugin.service().rollBook(tier, EnchantDef.Polarity.LIGHT, tier);
        if (roll == null) return;
        var book = plugin.items().makeEnchantBook(roll.def(), roll.level());
        if (book != null) event.getDrops().add(book);
    }
    private double chance(String kind, double fallback, double max) {
        double value = plugin.getConfig().getDouble("sources.mobs." + kind + "-chance-percent", fallback);
        return Double.isFinite(value) ? Math.clamp(value, 0, max) : fallback;
    }
}

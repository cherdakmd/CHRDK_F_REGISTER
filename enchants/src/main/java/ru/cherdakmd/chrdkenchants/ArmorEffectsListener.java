package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Броня: крепость (резка входящего), возмездие (отражение), последний шанс и феникс
 * (спасители с кулдаунами), страж льдов и громоотвод.
 */
public final class ArmorEffectsListener implements Listener {

    private final EnchantsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final java.util.Set<UUID> processing = new java.util.HashSet<>();

    public ArmorEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------- крепость/спасители ------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Map<String, Integer> enchants = bestArmorEnchants(victim);

        // Громоотвод: молнии не страшны, её удар даёт скорость
        if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING
                && enchants.containsKey("lightningrod")) {
            event.setCancelled(true);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    EnchantParams.seconds("lightningrod", 1) * 20, 0));
            victim.playSound(victim.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.4f, 1.5f);
            return;
        }

        // Крепость: максимальный уровень среди всех частей суммирует до -12%
        int fortified = enchants.getOrDefault("fortified", 0);
        if (fortified > 0) {
            event.setDamage(event.getDamage() * (1
                    - EnchantParams.percent("fortified", fortified) / 100.0));
        }

        double healthAfter = victim.getHealth() - event.getFinalDamage();

        // Феникс: от меня смертельный урон — воскрешение в пламени
        if (healthAfter <= 0 && enchants.containsKey("phoenix")
                && offCooldown(victim, "phoenix")) {
            event.setCancelled(true);
            resurrectPhoenix(victim);
            return;
        }

        // Последний шанс: почти погибли — щит и стойкость
        if (healthAfter > 0 && healthAfter <= 3.0
                && enchants.containsKey("laststand")
                && offCooldown(victim, "laststand")) {
            markCooldown(victim, "laststand");
            int seconds = EnchantParams.seconds("laststand", 1);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, seconds * 20, 0));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, seconds * 20, 0));
            plugin.messages().send(victim, "laststand-triggered",
                    "seconds", EnchantParams.cooldownSeconds("laststand"));
            victim.playSound(victim.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.4f);
        }
    }

    /* ------------------------------- отражение и холод ------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker) || attacker == victim) return;
        if (processing.contains(victim.getUniqueId())) return;
        Map<String, Integer> enchants = bestArmorEnchants(victim);

        int retribution = enchants.getOrDefault("retribution", 0);
        // возмездие только ближнему бою
        if (retribution > 0 && event.getDamager() instanceof Entity damager
                && !(damager instanceof org.bukkit.entity.Projectile)) {
            double reflect = event.getFinalDamage()
                    * EnchantParams.percent("retribution", retribution) / 100.0;
            if (reflect >= 0.5) {
                processing.add(victim.getUniqueId());
                try {
                    attacker.damage(reflect, victim);
                } finally {
                    processing.remove(victim.getUniqueId());
                }
                victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        attacker.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
            }
        }

        int iceward = enchants.getOrDefault("iceward", 0);
        if (iceward > 0 && !(event.getDamager() instanceof org.bukkit.entity.Projectile)) {
            int ticks = EnchantParams.seconds("iceward", iceward) * 20;
            attacker.setFreezeTicks(Math.min(attacker.getMaxFreezeTicks(),
                    attacker.getFreezeTicks() + ticks));
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE,
                    attacker.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
        }
    }

    /* ------------------------------- феникс ------------------------------- */

    private void resurrectPhoenix(Player victim) {
        markCooldown(victim, "phoenix");
        double max = victim.getAttribute(Attribute.MAX_HEALTH) != null
                ? victim.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
        victim.setHealth(Math.min(max, 8.0));
        victim.setFireTicks(0);
        int resistTicks = EnchantParams.seconds("phoenix-resist", 1) * 20;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, resistTicks, 1));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 10 * 20, 0));
        plugin.messages().send(victim, "phoenix-consumed",
                "seconds", EnchantParams.cooldownSeconds("phoenix"));
        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 0.8f);
        victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0, 0.5, 0),
                60, 0.6, 1.0, 0.6, 0.05);
        // «Феникс готов» — когда перезарядка закончится (если игрок ещё носит его)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline() && bestArmorEnchants(victim).containsKey("phoenix")) {
                plugin.messages().send(victim, "phoenix-armed");
            }
        }, EnchantParams.cooldownSeconds("phoenix") * 20L);
    }

    private boolean offCooldown(Player player, String id) {
        Long ready = cooldowns.get(cooldownKey(player.getUniqueId(), id));
        return ready == null || ready <= System.currentTimeMillis();
    }

    private void markCooldown(Player player, String id) {
        cooldowns.put(cooldownKey(player.getUniqueId(), id),
                System.currentTimeMillis() + EnchantParams.cooldownSeconds(id) * 1000L);
    }

    private static UUID cooldownKey(UUID uuid, String id) {
        return UUID.nameUUIDFromBytes((uuid + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /* ------------------------------- прочее ------------------------------- */

    /**
     * Сводный набор чар брони игрока: для каждой id берётся МАКСИМАЛЬНЫЙ уровень
     * среди надетых частей (в том числе случай, когда чарa занимает несколько слотов).
     */
    Map<String, Integer> bestArmorEnchants(Player player) {
        Map<String, Integer> best = new HashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            plugin.items().read(piece).forEach((id, level) ->
                    best.merge(id, level, Math::max));
        }
        return best;
    }
}

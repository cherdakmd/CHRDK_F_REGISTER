package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Стрелы: при выстреле чары лука копируются в PDC стрелы и позиция старта запоминается
 * (для снайперской). По попаданию срабатывают грозовая/взрывная/морозная/следящая.
 */
public final class BowEffectsListener implements Listener {

    private final EnchantsPlugin plugin;
    private final Map<UUID, Location> arrowOrigins = new HashMap<>();
    private BukkitTask sweepTask;

    public BowEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
        this.sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                arrowOrigins.entrySet().removeIf(entry -> {
                    Entity arrow = Bukkit.getEntity(entry.getKey());
                    return arrow == null || !arrow.isValid();
                }), 1200L, 1200L);
    }

    public void stopSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        arrowOrigins.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter) || event.getBow() == null) return;
        Map<String, Integer> enchants = plugin.items().read(event.getBow());
        if (enchants.isEmpty() || !(event.getProjectile() instanceof Arrow arrow)) return;
        plugin.items().writeEntity(arrow, enchants);
        if (enchants.containsKey("sniper")) {
            arrowOrigins.put(arrow.getUniqueId(), shooter.getLocation().clone());
        }
    }

    /** Бонус снайпера и охотника за головами — при уроне от стрелы. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        Map<String, Integer> enchants = plugin.items().readEntity(arrow);
        if (enchants.isEmpty()) return;

        int sniper = enchants.getOrDefault("sniper", 0);
        if (sniper > 0) {
            Location origin = arrowOrigins.get(arrow.getUniqueId());
            if (origin != null && origin.getWorld() == event.getEntity().getWorld()) {
                double distance = origin.distance(event.getEntity().getLocation());
                int per10 = EnchantParams.value("sniper", sniper);
                int cap = EnchantParams.value("sniper-cap", sniper);
                double bonus = Math.min(cap, distance / 10.0 * per10) / 100.0;
                event.setDamage(event.getDamage() * (1 + bonus));
                if (distance >= 30) {
                    event.getEntity().getWorld().spawnParticle(Particle.CRIT,
                            event.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
        if (enchants.containsKey("headhunter")
                && event.getEntity() instanceof Player) {
            event.setDamage(event.getDamage() * (1
                    + EnchantParams.percent("headhunter", 1) / 100.0));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        Map<String, Integer> enchants = plugin.items().readEntity(arrow);
        if (enchants.isEmpty()) return;
        arrowOrigins.remove(arrow.getUniqueId());
        Player shooter = arrow.getShooter() instanceof Player player ? player : null;
        Location hit = event.getHitEntity() instanceof LivingEntity victim
                ? victim.getLocation() : (event.getHitBlock() != null
                ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5) : arrow.getLocation());

        int storm = enchants.getOrDefault("stormarrow", 0);
        if (storm > 0) {
            arrow.getWorld().strikeLightningEffect(hit);
            double damage = EnchantParams.value("stormarrow", storm);
            for (Entity near : hit.getWorld().getNearbyEntities(hit, 2.0, 2.0, 2.0)) {
                if (near instanceof LivingEntity target && target != shooter) {
                    target.damage(damage, shooter == null ? arrow : shooter);
                }
            }
        }

        int explosive = enchants.getOrDefault("explosive", 0);
        if (explosive > 0) {
            float power = 0.8f * EnchantParams.value("explosive", explosive);
            // без поджога и урона блокам — целёсы улицы, больно врагам
            hit.getWorld().createExplosion(hit, power, false, false,
                    shooter);
        }

        if (event.getHitEntity() instanceof LivingEntity victim) {
            int frost = enchants.getOrDefault("frostarrow", 0);
            if (frost > 0) {
                int ticks = EnchantParams.seconds("frostarrow", frost) * 20;
                victim.setFreezeTicks(Math.min(victim.getMaxFreezeTicks(),
                        victim.getFreezeTicks() + ticks));
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0));
                victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0),
                        20, 0.4, 0.6, 0.4, 0.01);
            }
            if (enchants.containsKey("tracer")) {
                victim.setGlowing(true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> victim.setGlowing(false),
                        EnchantParams.seconds("tracer", 1) * 20L);
            }
        }
    }

    /** Голова жертвы падает при убийстве охотником за головами. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity killer = victim.getKiller() != null ? victim.getKiller()
                : victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hit
                ? hit.getEntity() : null;
        Map<String, Integer> enchants = killer instanceof Arrow arrow
                ? plugin.items().readEntity(arrow) : Map.of();
        if (!enchants.containsKey("headhunter")) return;
        ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(victim);
            head.setItemMeta(meta);
        }
        victim.getWorld().dropItemNaturally(victim.getLocation(), head);
        victim.getWorld().playSound(victim.getLocation(),
                Sound.ENTITY_WITHER_SKELETON_DEATH, 0.7f, 0.8f);
    }
}

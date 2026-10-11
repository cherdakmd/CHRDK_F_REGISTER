package ru.cherdakmd.chrdkenchants;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Эффекты ближнего боя (мечи/топоры): громобой, вампиризм, цепная молния, кровоточность,
 * паралич, казнь, берсерк — по удару; истребитель и пожиратель душ — по убийству.
 */
public final class MeleeEffectsListener implements Listener {

    private final EnchantsPlugin plugin;
    /** Защита от рекурсии: пока чара сама наносит урон, новых срабатываний не пропускаем. */
    private final Set<UUID> processing = new HashSet<>();

    public MeleeEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (processing.contains(attacker.getUniqueId())) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Map<String, Integer> enchants = plugin.items().read(weapon);
        if (enchants.isEmpty()) return;

        processing.add(attacker.getUniqueId());
        try {
            applyHitEffects(event, attacker, victim, enchants);
        } finally {
            processing.remove(attacker.getUniqueId());
        }
    }

    private void applyHitEffects(EntityDamageByEntityEvent event, Player attacker,
                                 LivingEntity victim, Map<String, Integer> enchants) {
        // Казнь: добивать почти павших
        EnchantDef execution = mountIf(enchants, "execution");
        if (execution != null) {
            int level = enchants.get("execution");
            double threshold = victim.getAttribute(Attribute.MAX_HEALTH) != null
                    ? victim.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.2 : 4;
            if (victim.getHealth() <= threshold) {
                event.setDamage(event.getDamage()
                        * EnchantParams.multiplier("execution", level));
                victim.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                        victim.getLocation().add(0, 1.0, 0), 6, 0.3, 0.3, 0.3, 0.02);
            }
        }

        // Берсерк: ниже 50% HP у нападающего — удар сильнее
        if (enchants.containsKey("berserk")) {
            double maxHp = attacker.getAttribute(Attribute.MAX_HEALTH) != null
                    ? attacker.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
            if (attacker.getHealth() <= maxHp * 0.5) {
                event.setDamage(event.getDamage()
                        * (1 + EnchantParams.percent("berserk", enchants.get("berserk")) / 100.0));
            }
        }

        double finalDamage = event.getFinalDamage();

        // Громобой
        int thunder = enchants.getOrDefault("thunderstrike", 0);
        if (thunder > 0 && roll(EnchantParams.chancePercent("thunderstrike", thunder))) {
            Location where = victim.getLocation();
            victim.getWorld().strikeLightningEffect(where);
            victim.damage(EnchantParams.thunderDamage(), attacker);
        }

        // Цепная молния: часть урона уходит ближайшим врагам
        int chain = enchants.getOrDefault("chainspark", 0);
        if (chain > 0) {
            int extra = EnchantParams.value("chainspark", chain);
            double arcDamage = finalDamage * 0.4;
            for (Entity nearby : victim.getNearbyEntities(3.5, 2.5, 3.5)) {
                if (extra <= 0) break;
                if (nearby instanceof LivingEntity target && target != victim
                        && target != attacker && !(target instanceof Player
                        && !canHit(attacker, target))) {
                    target.damage(arcDamage, attacker);
                    victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                            midpoint(victim, target), 12, 0.1, 0.1, 0.1, 0.05);
                    extra--;
                }
            }
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
                    0.3f, 1.8f);
        }

        // Вампиризм
        int lifesteal = enchants.getOrDefault("lifesteal", 0);
        if (lifesteal > 0) {
            double heal = finalDamage * EnchantParams.percent("lifesteal", lifesteal) / 100.0;
            healPlayer(attacker, heal);
        }

        // Кровоточность
        int bleeding = enchants.getOrDefault("bleeding", 0);
        if (bleeding > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                    EnchantParams.seconds("bleeding", bleeding) * 20, 0));
        }

        // Паралич
        int paralyze = enchants.getOrDefault("paralyze", 0);
        if (paralyze > 0 && roll(EnchantParams.chancePercent("paralyze", paralyze))) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 4));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, 3));
            victim.getWorld().spawnParticle(Particle.BLOCK, victim.getLocation().add(0, 0.5, 0),
                    20, 0.3, 0.5, 0.3, org.bukkit.Material.AMETHYST_BLOCK.createBlockData());
        }
    }

    /** Поручня убийств: истребитель и пожиратель душ. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        Map<String, Integer> enchants = plugin.items().read(weapon);
        int slayer = enchants.getOrDefault("slayer", 0);
        if (slayer > 0) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                    EnchantParams.seconds("slayer", slayer) * 20, 0));
        }
        int souleater = enchants.getOrDefault("souleater", 0);
        if (souleater > 0) {
            int bites = EnchantParams.value("souleater", souleater);
            killer.setFoodLevel(Math.min(20, killer.getFoodLevel() + bites));
            killer.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 4, 0));
            healPlayer(killer, souleater);
            killer.spawnParticle(Particle.SOUL, killer.getLocation().add(0, 1, 0),
                    8, 0.4, 0.6, 0.4, 0.01);
        }
    }

    /* ------------------------------- помощники ------------------------------- */

    private static EnchantDef mountIf(Map<String, Integer> enchants, String id) {
        return enchants.containsKey(id) ? EnchantRegistry.byId(id) : null;
    }

    private boolean roll(int chancePercent) {
        return chancePercent > 0
                && plugin.service().random().nextInt(100) < chancePercent;
    }

    private static void healPlayer(Player player, double amount) {
        double max = player.getAttribute(Attribute.MAX_HEALTH) != null
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
        player.setHealth(Math.min(max, player.getHealth() + Math.max(0.5, amount)));
    }

    /** Не цепим игроков, если уязвлён только PvE-состав (безопасность для союзников). */
    private static boolean canHit(Player attacker, LivingEntity target) {
        return target instanceof Monster || target instanceof Player victim && victim != attacker;
    }

    private static Location midpoint(LivingEntity from, LivingEntity to) {
        Location a = from.getLocation().add(0, from.getHeight() / 2, 0);
        Location b = to.getLocation().add(0, to.getHeight() / 2, 0);
        return a.add((b.getX() - a.getX()) / 2, (b.getY() - a.getY()) / 2,
                (z(b) - a.getZ()) / 2);
    }

    private static double z(Location location) {
        return location.getZ();
    }
}

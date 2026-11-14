package ru.cherdakmd.chrdkmobs;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Способности элитных мобов в бою: касательные эффекты при ударе, шипы,
 * призыв подмоги и телепорт к жертве. Способности лежат в PDC моба
 * ({@code elite-v1}), кулдауны тактических приёмов — в памяти слушателя.
 */
final class EliteAbilityListener implements Listener {

    private static final int THORNS_CAP = 4;
    private static final long MINIONS_COOLDOWN_MS = 20_000L;
    private static final long BLINK_COOLDOWN_MS = 8_000L;
    private static final double MINIONS_CHANCE = 0.35;
    private static final double BLINK_CHANCE = 0.25;
    private static final int TOUCH_FIRE_TICKS = 60;
    private static final int TOUCH_POTION_TICKS = 100;

    private final NamespacedKey eliteKey;
    private final Map<UUID, Long> minionsCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> blinkCooldown = new ConcurrentHashMap<>();
    private boolean reflecting;

    EliteAbilityListener(MobsPlugin plugin) {
        this.eliteKey = new NamespacedKey(plugin, "elite-v1");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (reflecting) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            LivingEntity attacker = attackerOf(event.getDamager());
            if (attacker != null) {
                onMobHitPlayer(player, attacker, event.getFinalDamage());
            }
        } else if (event.getEntity() instanceof LivingEntity victim
                && event.getDamager() instanceof Player player) {
            onPlayerHitMob(player, victim, event.getFinalDamage());
        }
    }

    private void onMobHitPlayer(Player player, LivingEntity mob, double damage) {
        EnumSet<EliteAbilities> abilities = abilitiesOf(mob);
        if (abilities.isEmpty()) {
            return;
        }
        if (abilities.contains(EliteAbilities.FIRE_TOUCH)) {
            player.setFireTicks(Math.max(player.getFireTicks(), TOUCH_FIRE_TICKS));
        }
        if (abilities.contains(EliteAbilities.VENOM)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, TOUCH_POTION_TICKS, 1));
        }
        if (abilities.contains(EliteAbilities.SLOW_TOUCH)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, TOUCH_POTION_TICKS, 1));
        }
        if (abilities.contains(EliteAbilities.WEAKNESS_TOUCH)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, TOUCH_POTION_TICKS, 1));
        }
        if (abilities.contains(EliteAbilities.KNOCKBACK)) {
            var push = player.getLocation().getDirection().multiply(1.1).setY(0.35);
            player.setVelocity(player.getVelocity().add(push));
        }
        if (abilities.contains(EliteAbilities.LIFESTEAL)) {
            mob.heal(Math.min(8.0, damage * 0.5));
        }
    }

    private void onPlayerHitMob(Player player, LivingEntity victim, double damage) {
        EnumSet<EliteAbilities> abilities = abilitiesOf(victim);
        if (abilities.isEmpty()) {
            return;
        }
        if (abilities.contains(EliteAbilities.THORNS)) {
            reflecting = true;
            try {
                player.damage(Math.min(THORNS_CAP, damage * 0.3), victim);
            } finally {
                reflecting = false;
            }
        }
        if (abilities.contains(EliteAbilities.MINIONS)) {
            summonMinions(victim);
        }
        if (abilities.contains(EliteAbilities.BLINK)) {
            blinkTo(victim, player);
        }
    }

    private void summonMinions(LivingEntity victim) {
        UUID id = victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = minionsCooldown.get(id);
        if (last != null && now - last < MINIONS_COOLDOWN_MS) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= MINIONS_CHANCE) {
            return;
        }
        minionsCooldown.put(id, now);
        for (int i = 0; i < 2; i++) {
            Location spot = victim.getLocation().clone().add(
                    ThreadLocalRandom.current().nextDouble(-2.0, 2.0), 0.0,
                    ThreadLocalRandom.current().nextDouble(-2.0, 2.0));
            try {
                // Призванные мобы (CUSTOM) не тируются и не награждаются.
                victim.getWorld().spawnEntity(spot, victim.getType());
            } catch (RuntimeException ignored) {
                // Неподходящий тип для спавна — пропускаем.
            }
        }
    }

    private void blinkTo(LivingEntity victim, Player player) {
        UUID id = victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = blinkCooldown.get(id);
        if (last != null && now - last < BLINK_COOLDOWN_MS) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= BLINK_CHANCE) {
            return;
        }
        Location behind = player.getLocation().clone()
                .add(player.getLocation().getDirection().multiply(-1.5).setY(0.0));
        behind.setYaw(victim.getLocation().getYaw());
        behind.setPitch(victim.getLocation().getPitch());
        if (victim.teleport(behind)) {
            blinkCooldown.put(id, now);
            victim.setFallDistance(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        minionsCooldown.remove(id);
        blinkCooldown.remove(id);
    }

    private EnumSet<EliteAbilities> abilitiesOf(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return EnumSet.noneOf(EliteAbilities.class);
        }
        return EliteAbilities.parse(
                living.getPersistentDataContainer().get(eliteKey, PersistentDataType.STRING));
    }

    private static LivingEntity attackerOf(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return null;
    }
}

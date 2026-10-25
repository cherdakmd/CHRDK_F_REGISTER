package ru.cherdakmd.chrdkclasses;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Применение умений классов (1.4.173).
 *
 * <p>Здесь только Bukkit: что именно делает умение и кому это можно. Числа —
 * в {@link ClassAbility}, момент последнего применения — в {@link ClassStore},
 * тексты — в {@code messages.yml}. Поэтому «в меню одно, в чате другое»
 * невозможно, а команда и кнопка меню зовут один и тот же метод.</p>
 *
 * <p>Правила, общие для всех умений: они не трогают блоки (ни пожара, ни
 * взрывов), не действуют на других игроков, не тратят валюту и молчат в мире,
 * где классы выключены. Маг бьёт только врагов ({@link Enemy}), поэтому коровы
 * и чужие питомцы остаются целы; жрец лечит только игроков.</p>
 */
final class AbilityService {

    /** Сколько союзников лечит жрец за одно применение: себя и четверых. */
    static final int CLERIC_TARGETS = 5;
    /** Угол между стрелами залпа в градусах. */
    static final double VOLLEY_STEP_DEGREES = 7.0;

    private final ClassesPlugin plugin;

    AbilityService(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Применяет умение класса.
     *
     * @return исход для команды и меню: {@code ability-used} или причина отказа
     */
    ClassResult use(Player player) {
        if (player == null) {
            return ClassResult.fail("player-only");
        }
        ClassConfig config = plugin.config();
        if (!config.enabled()) {
            return ClassResult.fail("disabled");
        }
        if (!config.abilitiesEnabled()) {
            return ClassResult.fail("ability-disabled");
        }
        UUID id = player.getUniqueId();
        PlayerClass playerClass = plugin.service().classOf(id);
        if (playerClass == null) {
            return ClassResult.fail("class-required");
        }
        if (player.isDead() || !player.isOnline()) {
            return ClassResult.fail("ability-dead");
        }
        ClassAbility ability = ClassAbility.of(playerClass);
        if (ability == null) {
            return ClassResult.fail("class-required");
        }
        long now = System.currentTimeMillis();
        long cooldown = ability.cooldownMillis(config.abilityCooldownMultiplier());
        long left = ClassAbility.remaining(plugin.store().abilityAt(id), now, cooldown);
        if (left > 0L) {
            return ClassResult.fail("ability-cooldown", "time", ClassMath.clockMillis(left));
        }
        // Сначала время, потом действие: два клика в одном тике обрабатываются
        // последовательно, и второй увидит уже записанный момент.
        plugin.store().setAbilityAt(id, now);
        plugin.requestSave();
        execute(player, ability);
        return ClassResult.ok("ability-used", "ability", playerClass.id());
    }

    /** Сколько ждать до следующего применения этого умения; 0 — готово. */
    long cooldownLeft(UUID player, PlayerClass playerClass) {
        ClassAbility ability = ClassAbility.of(playerClass);
        if (ability == null || player == null) {
            return 0L;
        }
        long cooldown = ability.cooldownMillis(plugin.config().abilityCooldownMultiplier());
        return ClassAbility.remaining(plugin.store().abilityAt(player), System.currentTimeMillis(),
                cooldown);
    }

    /* -------------------------------- исполнение ------------------------------- */

    private void execute(Player player, ClassAbility ability) {
        switch (ability) {
            case WARRIOR -> dash(player, ability);
            case ARCHER -> volley(player, ability);
            case MAGE -> frost(player, ability);
            case CLERIC -> heal(player, ability);
        }
    }

    /** Воин: рывок вперёд и короткая стойкость. */
    private void dash(Player player, ClassAbility ability) {
        Location eye = player.getLocation();
        Vector direction = eye.getDirection();
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            // Смотрим строго вверх или вниз — рывок вперёд по взгляду невозможен,
            // толкаем туда, куда повёрнуто тело.
            direction = new Vector(Math.sin(Math.toRadians(-eye.getYaw())), 0.0,
                    Math.cos(Math.toRadians(eye.getYaw())));
        }
        Vector push = direction.normalize().multiply(ability.power()).setY(0.32);
        player.setVelocity(push);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 0, false, false, true));
        player.getWorld().spawnParticle(Particle.CLOUD, eye.clone().add(0.0, 0.15, 0.0),
                12, 0.3, 0.1, 0.3, 0.02);
        player.getWorld().playSound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.4f);
    }

    /** Стрелок: три стрелы веером. */
    private void volley(Player player, ClassAbility ability) {
        Vector base = player.getLocation().getDirection().normalize();
        for (int index = -1; index <= 1; index++) {
            Vector turned = rotateY(base, index * VOLLEY_STEP_DEGREES);
            Arrow arrow = player.launchProjectile(Arrow.class,
                    turned.multiply(2.4).setY(turned.getY() + 0.05));
            if (arrow != null) {
                arrow.setDamage(ability.power());
                arrow.setCritical(false);
            }
        }
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0.0, 1.2, 0.0),
                10, 0.4, 0.2, 0.4, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 0.9f);
    }

    /** Маг: ледяная вспышка по врагам вокруг. */
    private void frost(Player player, ClassAbility ability) {
        Location center = player.getLocation();
        double radius = ability.radius();
        int hit = 0;
        for (var entity : player.getWorld().getNearbyEntities(center, radius, radius, radius,
                candidate -> candidate instanceof Enemy && candidate.isValid())) {
            if (!(entity instanceof LivingEntity victim) || victim.equals(player)) {
                continue;
            }
            victim.damage(ability.power(), player);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1,
                    false, true, true));
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0.0, 1.0, 0.0),
                    8, 0.25, 0.4, 0.25, 0.01);
            hit++;
        }
        player.getWorld().spawnParticle(Particle.SNOWFLAKE, center.clone().add(0.0, 1.0, 0.0),
                hit == 0 ? 12 : 20, radius / 2.0, 0.3, radius / 2.0, 0.02);
        player.getWorld().playSound(center, Sound.BLOCK_SNOW_BREAK, 0.9f, 0.7f);
    }

    /** Жрец: лечит себя и ближайших игроков. */
    private void heal(Player player, ClassAbility ability) {
        Location center = player.getLocation();
        List<Player> targets = new ArrayList<>();
        targets.add(player);
        for (var entity : player.getWorld().getNearbyEntities(center, ability.radius(),
                ability.radius(), ability.radius(), candidate -> candidate instanceof Player)) {
            if (entity instanceof Player other && !other.equals(player) && !other.isDead()) {
                targets.add(other);
            }
        }
        targets.sort(Comparator.comparingDouble(other -> other.getLocation().distanceSquared(center)));
        int healed = 0;
        for (Player target : targets) {
            if (healed >= CLERIC_TARGETS) {
                break;
            }
            double before = target.getHealth();
            healHalfHearts(target, ability.power());
            if (target.getHealth() > before) {
                healed++;
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 0,
                    false, true, true));
            target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0.0, 1.6, 0.0),
                    4, 0.3, 0.2, 0.3, 0.0);
        }
        player.getWorld().playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
    }

    /** Лечение в полусердцах: величина из {@link ClassAbility#power()}. */
    private static void healHalfHearts(Player player, double halfHearts) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
        double healed = player.getHealth() + halfHearts / 2.0;
        player.setHealth(Math.clamp(healed, 0.0, cap));
    }

    /** Поворот вектора вокруг вертикальной оси на угол в градусах. */
    static Vector rotateY(Vector vector, double degrees) {
        if (vector == null) {
            return new Vector();
        }
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = vector.getX() * cos - vector.getZ() * sin;
        double z = vector.getX() * sin + vector.getZ() * cos;
        return new Vector(x, vector.getY(), z);
    }
}

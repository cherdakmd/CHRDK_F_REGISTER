package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Исполнитель активных приёмов веток билда. Приёмы новые — приёмы классов не
 * переносятся. Сила приёма масштабируется ступенью престижа и уровнем ветки
 * ({@link BranchMath#power}); выносливость и таймеры общие с приёмами навыков
 * ({@link SkillAbilityStore}). Внешняя поддержка по умолчанию выключена и
 * требует явных действующих провайдеров проверок положительных эффектов.
 */
final class BranchAbilities {

    private final SkillsPlugin plugin;
    private final SkillAbilityStore store;

    BranchAbilities(SkillsPlugin plugin, SkillAbilityStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    int energy(Player player) {
        return store.energy(player == null ? null : player.getUniqueId());
    }

    long cooldownLeft(Player player, BranchAbility ability) {
        return store.cooldownLeft(player == null ? null : player.getUniqueId(),
                ability == null ? null : ability.id(), System.currentTimeMillis());
    }

    /**
     * Активирует приём; возвращает ключ сообщения ошибки или пустую строку.
     * Успех уже списал выносливость и включил таймер.
     */
    String use(Player player, BranchAbility ability) {
        if (player == null || ability == null || plugin.coreApi() == null) {
            return "ability-unavailable";
        }
        int level = plugin.skills().branchLevel(player, ability.branch());
        if (level < ability.unlockLevel()) {
            return "branch-locked";
        }
        int prestige = plugin.skills().rebirths(player);
        boolean support = isSupport(ability);
        Player planned = support ? supportTarget(player, ability) : player;
        String error = store.activate(player.getUniqueId(), ability.id(), ability.cost(),
                ability.cooldown(prestige), System.currentTimeMillis());
        if (!error.isEmpty()) {
            return error;
        }
        // Recheck all external evidence immediately before applying. Never redirect
        // to a different player if the initially selected target changed.
        Player target = support ? revalidateSupport(player, planned, () -> supportTarget(player, ability)) : player;
        apply(player, target, ability, BranchMath.power(prestige, level, 1.0), level);
        if (support) {
            if (target == player) plugin.messages().send(player, "support-self-only");
            else plugin.messages().send(player, "support-target", "name", target.getName());
        }
        return "";
    }

    private void apply(Player player, Player supportTarget, BranchAbility ability, double power, int level) {
        int seconds = 4 + Math.min(8, level / 2) + (int) Math.min(8, power - 1.0);
        switch (ability) {
            case CLEAVE -> damageAround(player, 3.0, 8.0 * power);
            case STANCE -> {
                effect(player, PotionEffectType.RESISTANCE, seconds, 1);
                effect(player, PotionEffectType.STRENGTH, seconds, 1);
            }
            case VOLLEY -> damageAround(player, 4.0, 7.0 * power);
            case LEAP -> {
                player.setVelocity(horizontal(player, 1.4).setY(0.7));
                effect(player, PotionEffectType.SPEED, seconds, 1);
                effect(player, PotionEffectType.JUMP_BOOST, seconds, 1);
            }
            case BOLT -> {
                damageAround(player, 4.0, 9.0 * power);
                slowAround(player, seconds);
            }
            case ORB -> {
                var target = player.getLocation().add(horizontal(player, 6));
                target.setYaw(player.getLocation().getYaw());
                target.setPitch(player.getLocation().getPitch());
                player.teleport(target);
            }
            case FAITH_SHIELD -> {
                Player target = supportTarget;
                effect(target, PotionEffectType.ABSORPTION, 8 + seconds, Math.max(0, Math.min(4, (int) (power * 1.5))));
            }
            case PURGE -> {
                Player target = supportTarget;
                for (PotionEffect effect : new ArrayList<>(target.getActivePotionEffects())) {
                    if (effect.getType() != null && isHarmful(effect.getType())) {
                        target.removePotionEffect(effect.getType());
                    }
                }
                effect(target, PotionEffectType.FIRE_RESISTANCE, seconds, 0);
            }
            case HEALING_SHIELD -> {
                Player target = supportTarget;
                effect(target, PotionEffectType.REGENERATION, 4 + seconds, Math.max(0, Math.min(3, (int) (power / 2))));
                effect(target, PotionEffectType.ABSORPTION, 4 + seconds, Math.max(0, Math.min(2, (int) (power / 3))));
            }
            case GRACE -> {
                Player target = supportTarget;
                double max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                        ? 20.0 : target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                target.setHealth(Math.min(max, target.getHealth() + 6.0 * power));
            }
            case BACKSTAB -> effect(player, PotionEffectType.STRENGTH, Math.max(3, seconds / 2), 2);
            case VANISH -> {
                effect(player, PotionEffectType.INVISIBILITY, Math.max(3, seconds / 2), 0);
                effect(player, PotionEffectType.SPEED, seconds, 1);
            }
        }
    }

    static Player revalidateSupport(Player self, Player planned, java.util.function.Supplier<Player> check) {
        if (self == null || planned == null || planned == self || check == null) return self;
        try { return check.get() == planned ? planned : self; }
        catch (RuntimeException | LinkageError e) { return self; }
    }

    static boolean isSupport(BranchAbility ability) {
        return ability == BranchAbility.FAITH_SHIELD || ability == BranchAbility.PURGE
                || ability == BranchAbility.HEALING_SHIELD || ability == BranchAbility.GRACE;
    }

    /** Default and any invalid configuration fail closed to the caster. */
    Player supportTarget(Player self, BranchAbility ability) {
        if (self == null || !isSupport(ability)) return self;
        try {
            if (!plugin.getConfig().getBoolean("support.external.enabled", false)) return self;
            var names = plugin.getConfig().getStringList("support.external.required-providers");
            if (names.isEmpty() || names.size() > 32 || names.stream().anyMatch(n -> n == null || n.isBlank())) return self;
            var required = new java.util.HashSet<String>();
            for (String name : names) required.add(name.trim());
            return SupportTargetResolver.resolve(self, SupportAim::find, SupportRelations::current,
                    (caster, target) -> SupportProtectionGate.check(caster, target, ability, required));
        } catch (RuntimeException | LinkageError e) {
            return self;
        }
    }


    private static void damageAround(Player player, double radius, double damage) {
        for (LivingEntity mob : nearbyEnemies(player, radius)) {
            mob.damage(damage);
        }
    }

    private static void slowAround(Player player, int seconds) {
        for (LivingEntity mob : nearbyEnemies(player, 4.0)) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 1,
                    true, false, true));
        }
    }

    private static List<LivingEntity> nearbyEnemies(Player player, double radius) {
        List<LivingEntity> mobs = new ArrayList<>();
        for (var entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)
                    && !living.isDead() && !MobRewardPolicy.temporary(living)) {
                mobs.add(living);
            }
        }
        return mobs;
    }

    private static void effect(Player player, PotionEffectType type, int seconds, int amplifier) {
        if (player == null || type == null || seconds <= 0) return;
        player.addPotionEffect(new PotionEffect(type, seconds * 20, Math.max(0, amplifier),
                true, false, true));
    }

    /** Горизонтальный вектор взгляда; взгляд вверх/вниз не даёт нулевой вектор. */
    private static org.bukkit.util.Vector horizontal(Player player, double length) {
        var direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1.0e-6) {
            direction = new org.bukkit.util.Vector(0, 0, 1);
        }
        return direction.normalize().multiply(length);
    }

    private static boolean isHarmful(PotionEffectType type) {
        return type == PotionEffectType.POISON
                || type == PotionEffectType.WITHER
                || type == PotionEffectType.WEAKNESS
                || type == PotionEffectType.SLOWNESS
                || type == PotionEffectType.MINING_FATIGUE
                || type == PotionEffectType.BLINDNESS
                || type == PotionEffectType.DARKNESS
                || type == PotionEffectType.HUNGER
                || type == PotionEffectType.LEVITATION
                || type == PotionEffectType.UNLUCK;
    }
}

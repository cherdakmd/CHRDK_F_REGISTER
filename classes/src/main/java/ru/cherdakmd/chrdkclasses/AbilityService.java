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
 * Применение умений классов (1.4.173; второе умение — 1.4.175).
 *
 * <p>Здесь только Bukkit: что именно делает умение и кому это можно. Числа —
 * в {@link ClassAbility}, момент последнего применения — в {@link ClassStore},
 * тексты — в {@code messages.yml}. Поэтому «в меню одно, в чате другое»
 * невозможно, а команда и кнопка меню зовут один и тот же метод.</p>
 *
 * <p>У класса два умения, и у каждого свой откат: {@link ClassStore#abilityAt}
 * и {@link ClassStore#ability2At}. Откат сокращается плюшкой ранга
 * ({@link RankPerks#cooldownFactor()}), но не ниже секунды.</p>
 *
 * <p>Правила, общие для всех умений: они не трогают блоки (ни пожара, ни
 * взрывов), не действуют на других игроков вредными эффектами, не тратят
 * валюту и молчат в мире, где классы выключены. Маг бьёт только врагов
 * ({@link Enemy}), поэтому коровы и чужие питомцы остаются целы; жрец и клич
 * воина помогают только игрокам.</p>
 */
final class AbilityService {

    /** Сколько союзников накрывает умение: себя и четверых (жрец и клич). */
    static final int SUPPORT_TARGETS = 5;
    /** Угол между стрелами залпа в градусах. */
    static final double VOLLEY_STEP_DEGREES = 7.0;
    /** Полуугол конуса испепеления в градусах. */
    static final double SCORCH_HALF_ANGLE = 35.0;
    /** Скорость прицельной стрелы. */
    static final double AIM_SPEED = 3.4;
    /** Сколько тиков горит враг после испепеления (три секунды). */
    static final int SCORCH_FIRE_TICKS = 60;

    private final ClassesPlugin plugin;

    AbilityService(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Применяет первое умение класса. */
    ClassResult use(Player player) {
        return use(player, ClassAbility.FIRST);
    }

    /**
     * Применяет умение слота.
     *
     * @param slot 1 — первое умение, 2 — второе; иное значение читается как первое
     * @return исход для команды и меню: {@code ability-used} или причина отказа
     */
    ClassResult use(Player player, int slot) {
        if (player == null) {
            return ClassResult.fail("player-only");
        }
        if (!player.hasPermission("chrdkclasses.ability")) {
            return ClassResult.fail("no-permission");
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
        if (!plugin.ready(player)) return ClassResult.fail("development-pending");
        if (plugin.gameplay().inPvp(id) || !plugin.gameplay().context(player)) return ClassResult.fail("development-pve-only");
        ClassAbility ability = plugin.service().development().equipped(id, slot);
        if (ability == null) {
            return ClassResult.fail("class-required");
        }
        long now = System.currentTimeMillis();
        long cooldown = cooldownMillis(id, ability);
        long left = ClassAbility.remaining(plugin.store().lastAbility(id, ability.key()), now, cooldown);
        if (left > 0L) {
            return ClassResult.fail("ability-cooldown", "time", ClassMath.clockMillis(left),
                    "ability", ability.key());
        }
        // Сначала время, потом действие: два клика в одном тике обрабатываются
        // последовательно, и второй увидит уже записанный момент.
        plugin.store().useAbility(id, ability.key(), now);
        plugin.requestSave();
        execute(player, ability);
        return ClassResult.ok("ability-used", "ability", ability.key());
    }

    /** Полный откат умения с учётом конфига и ранга игрока. */
    long cooldownMillis(UUID player, ClassAbility ability) {
        if (ability == null) {
            return 1_000L;
        }
        RankPerks perks = plugin.service().perks(player);
        return ability.cooldownMillis(plugin.config().abilityCooldownMultiplier(),
                perks.cooldownFactor());
    }

    /** Сколько ждать до следующего применения первого умения; 0 — готово. */
    long cooldownLeft(UUID player, PlayerClass playerClass) {
        return cooldownLeft(player, playerClass, ClassAbility.FIRST);
    }

    /** Сколько ждать до следующего применения умения слота; 0 — готово. */
    long cooldownLeft(UUID player, PlayerClass playerClass, int slot) {
        ClassAbility ability = plugin.service().development() == null ? ClassAbility.of(playerClass, slot)
                : plugin.service().development().equipped(player, slot);
        if (ability == null || player == null) {
            return 0L;
        }
        return ClassAbility.remaining(plugin.store().lastAbility(player, ability.key()),
                System.currentTimeMillis(), cooldownMillis(player, ability));
    }

    private long lastUseAt(UUID player, int slot) {
        return slot == ClassAbility.SECOND
                ? plugin.store().ability2At(player) : plugin.store().abilityAt(player);
    }

    private void setUseAt(UUID player, int slot, long now) {
        if (slot == ClassAbility.SECOND) {
            plugin.store().setAbility2At(player, now);
        } else {
            plugin.store().setAbilityAt(player, now);
        }
    }

    /* -------------------------------- исполнение ------------------------------- */

    private void execute(Player player, ClassAbility ability) {
        switch (ability) {
            case WARRIOR -> dash(player, ability);
            case WARRIOR_CRY -> cry(player, ability);
            case ARCHER -> volley(player, ability);
            case ARCHER_AIM -> aim(player, ability);
            case MAGE -> frost(player, ability);
            case MAGE_SCORCH -> scorch(player, ability);
            case CLERIC -> heal(player, ability);
            case CLERIC_WARD -> ward(player, ability);
            case WARRIOR_BASH, WARRIOR_EARTH, ARCHER_SNARE, ARCHER_RETREAT, MAGE_CHAIN, MAGE_SEAL, CLERIC_LIGHT, CLERIC_PURIFY -> extra(player,ability);
        }
    }

    /** Воин, слот 1: рывок вперёд и короткая стойкость. */
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
        plugin.gameplay().ward(player, 3, 3);
        player.getWorld().spawnParticle(Particle.CLOUD, eye.clone().add(0.0, 0.15, 0.0),
                12, 0.3, 0.1, 0.3, 0.02);
        player.getWorld().playSound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.4f);
    }

    /**
     * Воин, слот 2: боевой клич — поглощение и стойкость себе и союзникам.
     *
     * <p>Помощь, а не урон: клич не бьёт врагов, поэтому его нельзя «слить» в
     * чужой бой и он не спорит с испепелением мага.</p>
     */
    private void cry(Player player, ClassAbility ability) {
        int amplifier = absorptionAmplifier(ability.power());
        // allies() всегда включает самого игрока: клич срабатывает и в одиночку.
        for (Player target : allies(player, ability.radius())) {
            plugin.gameplay().ward(target, ability.power(), 8);
            target.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                    target.getLocation().add(0.0, 1.2, 0.0), 6, 0.3, 0.4, 0.3, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.4f, 1.4f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0.0, 1.0, 0.0),
                16, 1.2, 0.3, 1.2, 0.02);
    }

    /** Стрелок, слот 1: три стрелы веером. */
    private void volley(Player player, ClassAbility ability) {
        Vector base = player.getLocation().getDirection().normalize();
        for (int index = -1; index <= 1; index++) {
            Vector velocity = rotateY(base, index * VOLLEY_STEP_DEGREES).multiply(2.4);
            // Чуть вверх, иначе с близкой дистанции стрелы уходят в пол.
            velocity.setY(velocity.getY() + 0.05);
            Arrow arrow = player.launchProjectile(Arrow.class, velocity);
            if (arrow != null) {
                plugin.gameplay().tag(arrow);
                arrow.setDamage(ability.power());
                arrow.setCritical(false);
            }
        }
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0.0, 1.2, 0.0),
                10, 0.4, 0.2, 0.4, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 0.9f);
    }

    /**
     * Стрелок, слот 2: прицельный выстрел.
     *
     * <p>Одна стрела вместо трёх: пробивает цель насквозь и бьёт сильнее.
     * Скорость выше обычной, чтобы вести цель было проще, — но не настолько,
     * чтобы стрела «телепортировалась» в моба.</p>
     */
    private void aim(Player player, ClassAbility ability) {
        Vector velocity = player.getLocation().getDirection().normalize().multiply(AIM_SPEED);
        Arrow arrow = player.launchProjectile(Arrow.class, velocity);
        if (arrow != null) {
            plugin.gameplay().tag(arrow);
            arrow.setDamage(ability.power());
            arrow.setCritical(false);
            arrow.setPierceLevel(1);
        }
        player.getWorld().spawnParticle(Particle.CRIT,
                player.getLocation().add(0.0, 1.2, 0.0), 12, 0.2, 0.2, 0.2, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.7f, 1.6f);
    }

    /** Маг, слот 1: ледяная вспышка по врагам вокруг. */
    private void frost(Player player, ClassAbility ability) {
        Location center = player.getLocation();
        double radius = ability.radius();
        int hit = 0;
        for (var entity : player.getWorld().getNearbyEntities(center, radius, radius, radius,
                candidate -> candidate instanceof Enemy && candidate.isValid())) {
            // Enemy — только враждебные мобы: чужие питомцы и коровы целы.
            if (!(entity instanceof LivingEntity victim)) {
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

    /**
     * Маг, слот 2: испепеление конусом.
     *
     * <p>Бьёт только тех, кто впереди: конус, а не круг — иначе пришлось бы
     * выбирать между вспышкой и этим умением по цифрам, а не по ситуации.</p>
     */
    private void scorch(Player player, ClassAbility ability) {
        Location eye = player.getLocation().add(0.0, 1.0, 0.0);
        Vector direction = player.getLocation().getDirection().normalize();
        double radius = ability.radius();
        int hit = 0;
        for (var entity : player.getWorld().getNearbyEntities(eye, radius, radius, radius,
                candidate -> candidate instanceof Enemy && candidate.isValid())) {
            if (!(entity instanceof LivingEntity victim)) {
                continue;
            }
            Vector toTarget = victim.getLocation().add(0.0, 0.9, 0.0).toVector().subtract(eye.toVector());
            if (!inCone(direction, toTarget, SCORCH_HALF_ANGLE)) {
                continue;
            }
            victim.damage(ability.power(), player);
            victim.setFireTicks(Math.max(victim.getFireTicks(), SCORCH_FIRE_TICKS));
            player.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0.0, 1.0, 0.0),
                    12, 0.3, 0.5, 0.3, 0.02);
            hit++;
        }
        // Пламя летит вперёд даже без попаданий — видно, куда ушло умение.
        for (int step = 1; step <= 6; step++) {
            Location point = eye.clone().add(direction.clone().multiply(step * 0.8));
            player.getWorld().spawnParticle(Particle.FLAME, point, hit == 0 ? 3 : 1,
                    0.15, 0.15, 0.15, 0.01);
        }
        player.getWorld().playSound(eye, Sound.ITEM_FIRECHARGE_USE, 0.9f, 0.8f);
    }

    /** Жрец, слот 1: лечит себя и ближайших игроков. */
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
            if (!plugin.gameplay().supportAllowed(target)) continue;
            if (healed >= SUPPORT_TARGETS) {
                break;
            }
            double before = target.getHealth();
            healHalfHearts(target, ability.power());
            if (target.getHealth() > before) {
                healed++;
            }
            plugin.gameplay().healed(player, target, target.getHealth() - before);
            target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0.0, 1.6, 0.0),
                    4, 0.3, 0.2, 0.3, 0.0);
        }
        player.getWorld().playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
    }

    /**
     * Жрец, слот 2: оберег — поглощение и снятие вредных эффектов у союзников.
     *
     * <p>Лечение у жреца уже есть в первом слоте, поэтому оберег работает до
     * удара: поглощение и чистое состояние. Яд не снимается, чтобы оберег не
     * заменял молоко и не обесценивал предметы.</p>
     */
    private void ward(Player player, ClassAbility ability) {
        int amplifier = absorptionAmplifier(ability.power());
        for (Player target : allies(player, ability.radius())) {
            plugin.gameplay().ward(target, ability.power(), 12);
            target.getWorld().spawnParticle(Particle.END_ROD,
                    target.getLocation().add(0.0, 1.2, 0.0), 10, 0.4, 0.6, 0.4, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
    }

    private void extra(Player player, ClassAbility ability) {
        if (ability == ClassAbility.ARCHER_RETREAT) {
            Vector direction = player.getLocation().getDirection().setY(0).normalize().multiply(-ability.power()).setY(.25);
            player.setVelocity(direction); return;
        }
        if (ability == ClassAbility.CLERIC_PURIFY) {
            for (Player target : allies(player, ability.radius())) if (plugin.gameplay().supportAllowed(target)) {
                double before=target.getHealth();
                healHalfHearts(target,ability.power());
                plugin.gameplay().healed(player,target,target.getHealth()-before);
                for (PotionEffectType type : harmfulEffects()) target.removePotionEffect(type);
            }
            return;
        }
        List<LivingEntity> enemies = player.getNearbyEntities(ability.radius(),4,ability.radius()).stream()
                .filter(e -> e instanceof Enemy && e instanceof LivingEntity living && !living.isDead())
                .map(e -> (LivingEntity)e).sorted(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation()))).toList();
        int limit = ability == ClassAbility.WARRIOR_BASH || ability == ClassAbility.ARCHER_SNARE ? 1 : 3;
        for (LivingEntity enemy : enemies.stream().limit(limit).toList()) {
            double damage=ability.power();
            if (ability == ClassAbility.CLERIC_LIGHT) {
                boolean undead=switch(enemy.getType()) {
                    case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, WITHER_SKELETON, PHANTOM, WITHER, ZOMBIFIED_PIGLIN, ZOGLIN -> true;
                    default -> false;
                };
                if (!undead) damage/=2;
            }
            enemy.damage(damage,player);
            if (ability == ClassAbility.WARRIOR_BASH || ability == ClassAbility.WARRIOR_EARTH) {
                Vector push=enemy.getLocation().toVector().subtract(player.getLocation().toVector());
                if(push.lengthSquared()>.01) enemy.setVelocity(push.normalize().multiply(.5).setY(.25));
            }
            if (ability == ClassAbility.ARCHER_SNARE || ability == ClassAbility.MAGE_SEAL || ability == ClassAbility.WARRIOR_EARTH)
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,80,1));
            if (ability == ClassAbility.MAGE_SEAL) enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,80,0));
        }
        player.getWorld().spawnParticle(Particle.ENCHANT,player.getLocation().add(0,1,0),20,1,1,1,.01);
    }

    /* -------------------------------- помощники ------------------------------- */

    /**
     * Эффекты, которые снимает оберег жреца: вредные, но не смертельные.
     *
     * <p>Массив строится при вызове, а не при загрузке класса: в Paper типы
     * эффектов привязаны к реестру сервера, и статическое поле сделало бы
     * {@link AbilityService} невызываемым вне сервера — включая тесты чистой
     * математики (конус, поглощение).</p>
     */
    private static PotionEffectType[] harmfulEffects() {
        return new PotionEffectType[]{
                PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON,
                PotionEffectType.WITHER, PotionEffectType.NAUSEA, PotionEffectType.BLINDNESS,
                PotionEffectType.DARKNESS, PotionEffectType.UNLUCK};
    }

    /**
     * Поглощение по величине в полусердцах.
     *
     * <p>Уровень зелья даёт 4 HP (два сердца): {@code power = 4} — первый
     * уровень, {@code 8} — второй. Округление вниз и хотя бы первый уровень:
     * оберег не должен превращаться в «пустую трату» из-за дробей.</p>
     */
    static int absorptionAmplifier(double halfHearts) {
        int level = (int) Math.round(Math.max(0.0, halfHearts) / 4.0);
        return Math.clamp(level, 1, 4) - 1;
    }

    /**
     * Попадает ли цель в конус.
     *
     * <p>Чистая математика — поэтому проверяется тестом: {@code direction} и
     * {@code toTarget} не обязаны быть нормализованными, нулевой вектор не
     * попадает никуда, а угол сравнивается в градусах.</p>
     */
    static boolean inCone(Vector direction, Vector toTarget, double halfAngleDegrees) {
        if (direction == null || toTarget == null) {
            return false;
        }
        double length = direction.length() * toTarget.length();
        if (length < 1.0E-6) {
            return false;
        }
        double cos = direction.dot(toTarget) / length;
        return cos >= Math.cos(Math.toRadians(Math.clamp(halfAngleDegrees, 0.0, 180.0)));
    }

    /** Себя и ближайших живых игроков в радиусе; больше {@link #SUPPORT_TARGETS} не берём. */
    private List<Player> allies(Player player, double radius) {
        Location center = player.getLocation();
        List<Player> targets = new ArrayList<>();
        targets.add(player);
        for (var entity : player.getWorld().getNearbyEntities(center, radius, radius, radius,
                candidate -> candidate instanceof Player && !candidate.isDead())) {
            if (entity instanceof Player other && !other.equals(player)) {
                targets.add(other);
            }
        }
        targets.sort(Comparator.comparingDouble(other -> other.getLocation().distanceSquared(center)));
        return targets.size() > SUPPORT_TARGETS
                ? targets.subList(0, SUPPORT_TARGETS) : targets;
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

package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Slime;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Активные способности находок: перезарядка и само действие.
 *
 * <p>Способность срабатывает по правому клику. У лука и арбалета — только в
 * приседе: иначе плагин съедал бы обычный выстрел. Перезарядка считается
 * отдельно по каждому предмету, поэтому два клинка с разными способностями не
 * блокируют друг друга.</p>
 *
 * <p>Все действия ограничены: урон и эффекты получают только враждебные мобы
 * ({@link Monster} и слизи), игроки и мир не страдают. Радиус и сила берутся
 * из каталога, поэтому «дальность» способности нельзя раскачать — потолок
 * зашит в предмет.</p>
 */
public final class TreasureAbilities {

    /** Не чаще раза в столько миллисекунд напоминаем о перезарядке. */
    private static final long NOTICE_MILLIS = 1_500L;

    private final TreasuresPlugin plugin;
    private final Map<UUID, Map<String, Long>> readyAt = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> notifiedAt = new ConcurrentHashMap<>();

    public TreasureAbilities(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Пытается применить способность предмета.
     *
     * @return {@code true}, если событие нужно отменить (способность или
     *         перезарядка «съели» клик)
     */
    public boolean use(Player player, TreasureItemDef def) {
        if (player == null || def == null || def.ability() == null) {
            return false;
        }
        if (!plugin.config().abilitiesEnabled()) {
            return false;
        }
        if (TreasureItems.bowLike(def.material()) && !player.isSneaking()) {
            return false;
        }
        if (!player.hasPermission("chrdktreasures.ability")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        TreasureAbility ability = def.ability();
        long now = System.currentTimeMillis();
        long ready = readyAt.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
                .getOrDefault(def.id(), 0L);
        if (now < ready) {
            Map<String, Long> notices = notifiedAt.computeIfAbsent(player.getUniqueId(),
                    key -> new ConcurrentHashMap<>());
            if (now - notices.getOrDefault(def.id(), 0L) >= NOTICE_MILLIS) {
                notices.put(def.id(), now);
                long seconds = Math.max(1L, (ready - now + 999L) / 1000L);
                plugin.messages().send(player, "ability-cooldown",
                        "name", abilityName(ability),
                        "seconds", String.valueOf(seconds));
            }
            return true;
        }
        execute(player, ability);
        readyAt.get(player.getUniqueId())
                .put(def.id(), now + ability.cooldownSeconds() * 1000L);
        plugin.messages().send(player, "ability-used",
                "name", abilityName(ability),
                "cooldown", String.valueOf(ability.cooldownSeconds()));
        return true;
    }

    /** Снимает перезарядки игрока при выходе — память не растёт. */
    public void forget(UUID playerId) {
        if (playerId != null) {
            readyAt.remove(playerId);
            notifiedAt.remove(playerId);
        }
    }

    /** Сколько секунд осталось до готовности предмета (0 — готов). */
    public long remaining(Player player, TreasureItemDef def) {
        if (player == null || def == null) {
            return 0L;
        }
        Map<String, Long> cooldowns = readyAt.get(player.getUniqueId());
        if (cooldowns == null) {
            return 0L;
        }
        long ready = cooldowns.getOrDefault(def.id(), 0L);
        long left = ready - System.currentTimeMillis();
        return left <= 0L ? 0L : (left + 999L) / 1000L;
    }

    private void execute(Player player, TreasureAbility ability) {
        int seconds = Math.max(1, (int) Math.round(ability.power()));
        switch (ability.kind()) {
            case SENSE -> monsters(player, ability.radius(), target ->
                    target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                            seconds * 20, 0, true, false, true)));
            case BLAST -> monsters(player, ability.radius(), target -> {
                target.damage(ability.power(), player);
                Vector push = target.getLocation().toVector()
                        .subtract(player.getLocation().toVector());
                if (push.lengthSquared() > 0.01) {
                    target.setVelocity(push.normalize().multiply(0.8).setY(0.45));
                }
            });
            case FLAME_NOVA -> monsters(player, ability.radius(), target ->
                    target.setFireTicks(Math.max(target.getFireTicks(), seconds * 20)));
            case FROST_NOVA -> monsters(player, ability.radius(), target ->
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                            seconds * 20, 1, true, false, true)));
            case HEAL -> player.heal(ability.power() * 2.0);
            case SHIELD -> player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    seconds * 20, 0, true, false, true));
            case DASH -> {
                Vector direction = player.getLocation().getDirection().normalize()
                        .multiply(ability.power());
                direction.setY(Math.max(0.25, direction.getY()));
                player.setVelocity(direction);
                player.setFallDistance(0.0f);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                        60, 0, true, false, true));
            }
            case LEAP -> {
                player.setVelocity(new Vector(0, ability.power(), 0));
                player.setFallDistance(0.0f);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                        100, 0, true, false, true));
            }
            case AQUA -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,
                        seconds * 20, 0, true, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,
                        seconds * 20, 0, true, false, true));
            }
            /* ------------------- перенос из датапака Mine Treasure ------------------- */
            case EXPLOSION -> {
                monsters(player, ability.radius(), target -> {
                    target.damage(ability.power(), player);
                    pushAway(player, target, 1.0);
                });
                player.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,
                        player.getLocation().add(0.0, 1.0, 0.0), 1);
            }
            case FROST_STRIKE -> monsters(player, ability.radius(), target -> {
                target.damage(ability.power(), player);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        60, 1, true, false, true));
            });
            case WITHER_STRIKE -> monsters(player, ability.radius(), target -> {
                target.damage(ability.power(), player);
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        100, 1, true, false, true));
            });
            case VENOM -> monsters(player, ability.radius(), target -> {
                target.damage(ability.power(), player);
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                        100, 1, true, false, true));
            });
            case TIDE -> monsters(player, ability.radius(), target -> {
                Vector pull = player.getLocation().toVector()
                        .subtract(target.getLocation().toVector());
                if (pull.lengthSquared() > 0.01) {
                    target.setVelocity(pull.normalize().multiply(0.9).setY(0.3));
                }
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        60, 1, true, false, true));
                target.damage(ability.power() * 0.5, player);
            });
            case GUST -> monsters(player, ability.radius(), target -> {
                pushAway(player, target, 1.4);
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                        100, 1, true, false, true));
                target.damage(ability.power() * 0.5, player);
            });
            case BLINK -> blink(player, ability.power());
            case FRENZY -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        seconds * 20, 1, true, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        seconds * 20, 1, true, false, true));
            }
            case BORE -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,
                        seconds * 20, 1, true, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        seconds * 20, 0, true, false, true));
            }
            case SPIKES -> spikes(player, seconds, ability.radius());
            case NECROMANCY -> necromancy(player, seconds);
            case HORN -> {
                horn(player, ability.radius());
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        seconds * 20, 0, true, false, true));
            }
            default -> {
                // новые виды способностей обязаны попасть в switch — компилятор
                // подскажет, но защита от «молча ничего не сделать» остаётся
            }
        }
        player.getWorld().spawnParticle(particleOf(ability.kind()),
                player.getLocation().add(0.0, 1.0, 0.0), 18, 0.6, 0.4, 0.6, 0.02);
        player.playSound(player.getLocation(), soundOf(ability.kind()), 0.9f, 1.0f);
    }

    /** Отбрасывает цель от игрока; сила зависит от вида способности. */
    private void pushAway(Player player, LivingEntity target, double strength) {
        Vector push = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (push.lengthSquared() > 0.01) {
            target.setVelocity(push.normalize().multiply(strength).setY(0.4));
        }
    }

    /** Перенос по взгляду: останавливается перед первой стеной и не сажает в блок. */
    private void blink(Player player, double distance) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location target = eye.clone().add(direction.clone().multiply(distance));
        RayTraceResult trace = player.getWorld().rayTraceBlocks(eye, direction, distance,
                FluidCollisionMode.NEVER, true);
        if (trace != null && trace.getHitPosition() != null) {
            target = trace.getHitPosition().toLocation(player.getWorld())
                    .subtract(direction.clone().multiply(1.0));
        }
        // Если место занято, отступаем назад: игрок не должен оказаться в камне.
        for (int step = 0; step < 3; step++) {
            if (target.getBlock().isPassable() && target.clone().add(0, 1, 0).getBlock().isPassable()) {
                break;
            }
            target.subtract(direction.clone().multiply(1.0));
        }
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleport(target);
        player.setFallDistance(0.0f);
    }

    /** Шипы: зона урона вокруг игрока, пока действует способность. */
    private void spikes(Player player, int seconds, int radius) {
        int period = 10;
        int total = Math.max(period, seconds * 20);
        new org.bukkit.scheduler.BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                elapsed += period;
                if (!player.isOnline() || elapsed > total) {
                    cancel();
                    return;
                }
                player.getWorld().spawnParticle(Particle.CRIT,
                        player.getLocation().add(0.0, 0.2, 0.0), 8, 1.2, 0.1, 1.2, 0.02);
                monsters(player, Math.max(1, radius), target -> target.damage(2.0, player));
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    /** Некромантия: два временных волка, которые воюют на стороне игрока. */
    private void necromancy(Player player, int seconds) {
        Location base = player.getLocation();
        for (int index = 0; index < 2; index++) {
            Location spot = base.clone().add(index == 0 ? 1.5 : -1.5, 0, 1.0);
            if (!spot.getBlock().isPassable()) {
                spot = base.clone();
            }
            try {
                Wolf wolf = player.getWorld().spawn(spot, Wolf.class);
                // Владельца не назначаем: волк нужен как временный союзник,
                // а не как питомец в списке приручённых. Приручённый волк и
                // без владельца не трогает игроков и служит до таймера.
                wolf.setTamed(true);
                wolf.setAdult();
                wolf.setCustomNameVisible(false);
                int life = Math.max(40, seconds * 20);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (wolf.isValid()) {
                        wolf.getWorld().spawnParticle(Particle.SOUL, wolf.getLocation(), 12, 0.3, 0.4, 0.3, 0.02);
                        wolf.remove();
                    }
                }, life);
            } catch (RuntimeException ignored) {
                // Лимит мобов в чанке или запрет мира: способность просто не призывает волка.
            }
        }
    }

    /** Горн: союзники рядом получают сопротивление, мобы — отброс и урон. */
    private void horn(Player player, int radius) {
        if (radius <= 0) {
            return;
        }
        for (org.bukkit.entity.Entity entity : player.getWorld().getNearbyEntities(
                player.getLocation(), radius, radius, radius)) {
            if (entity instanceof Player ally && !ally.equals(player)) {
                ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                        120, 0, true, false, true));
                ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        120, 0, true, false, true));
            } else if (entity instanceof LivingEntity living
                    && (entity instanceof Monster || entity instanceof Slime)) {
                pushAway(player, living, 1.2);
                living.damage(2.0, player);
            }
        }
    }

    /** Враждебные мобы в радиусе; игроки и мир не затрагиваются. */
    private void monsters(Player player, int radius, java.util.function.Consumer<LivingEntity> action) {
        if (radius <= 0) {
            return;
        }
        for (org.bukkit.entity.Entity entity : player.getWorld().getNearbyEntities(
                player.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (entity instanceof Player || !(entity instanceof Monster || entity instanceof Slime)) {
                continue;
            }
            action.accept(living);
        }
    }

    private Messages.Trusted abilityName(TreasureAbility ability) {
        return Messages.trusted(plugin.messages().raw(ability.kind().textKey()));
    }

    private static Particle particleOf(TreasureAbility.Kind kind) {
        return switch (kind) {
            case SENSE -> Particle.ELECTRIC_SPARK;
            case BLAST -> Particle.CRIT;
            case DASH, LEAP -> Particle.CLOUD;
            case FLAME_NOVA -> Particle.FLAME;
            case FROST_NOVA -> Particle.SNOWFLAKE;
            case HEAL -> Particle.HAPPY_VILLAGER;
            case SHIELD -> Particle.ENCHANT;
            case AQUA -> Particle.BUBBLE;
            case EXPLOSION -> Particle.EXPLOSION;
            case FROST_STRIKE -> Particle.SNOWFLAKE;
            case WITHER_STRIKE -> Particle.SOUL;
            case VENOM -> Particle.ITEM_SLIME;
            case TIDE -> Particle.BUBBLE_POP;
            case BLINK -> Particle.PORTAL;
            case GUST -> Particle.CLOUD;
            case FRENZY -> Particle.ANGRY_VILLAGER;
            case BORE -> Particle.CRIT;
            case SPIKES -> Particle.DAMAGE_INDICATOR;
            case NECROMANCY -> Particle.SOUL_FIRE_FLAME;
            case HORN -> Particle.NOTE;
        };
    }

    private static Sound soundOf(TreasureAbility.Kind kind) {
        return switch (kind) {
            case SENSE -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            case BLAST -> Sound.ENTITY_GENERIC_EXPLODE;
            case DASH, LEAP -> Sound.ENTITY_ENDERMAN_TELEPORT;
            case FLAME_NOVA -> Sound.ITEM_FIRECHARGE_USE;
            case FROST_NOVA -> Sound.BLOCK_GLASS_BREAK;
            case HEAL -> Sound.ITEM_TOTEM_USE;
            case SHIELD -> Sound.BLOCK_BEACON_ACTIVATE;
            case AQUA -> Sound.ENTITY_GENERIC_SPLASH;
            case EXPLOSION -> Sound.ENTITY_GENERIC_EXPLODE;
            case FROST_STRIKE -> Sound.BLOCK_GLASS_BREAK;
            case WITHER_STRIKE -> Sound.ENTITY_WITHER_HURT;
            case VENOM -> Sound.ENTITY_SPIDER_AMBIENT;
            case TIDE -> Sound.ITEM_BUCKET_EMPTY;
            case BLINK -> Sound.ENTITY_ENDERMAN_TELEPORT;
            case GUST -> Sound.ITEM_TRIDENT_RIPTIDE_1;
            case FRENZY -> Sound.ENTITY_RAVAGER_ROAR;
            case BORE -> Sound.BLOCK_ANVIL_LAND;
            case SPIKES -> Sound.BLOCK_POINTED_DRIPSTONE_LAND;
            case NECROMANCY -> Sound.ENTITY_WITHER_SKELETON_AMBIENT;
            case HORN -> Sound.ITEM_GOAT_HORN_SOUND_0;
        };
    }
}

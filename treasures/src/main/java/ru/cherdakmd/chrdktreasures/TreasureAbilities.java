package ru.cherdakmd.chrdktreasures;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
            default -> {
                // новые виды способностей обязаны попасть в switch — компилятор
                // подскажет, но защита от «молча ничего не сделать» остаётся
            }
        }
        player.getWorld().spawnParticle(particleOf(ability.kind()),
                player.getLocation().add(0.0, 1.0, 0.0), 18, 0.6, 0.4, 0.6, 0.02);
        player.playSound(player.getLocation(), soundOf(ability.kind()), 0.9f, 1.0f);
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
        };
    }
}

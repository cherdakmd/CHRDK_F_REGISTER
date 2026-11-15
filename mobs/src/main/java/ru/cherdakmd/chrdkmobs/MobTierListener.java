package ru.cherdakmd.chrdkmobs;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Тиры мобов на живых событиях. Естественные враги получают тир при
 * появлении (дальше от спавна мира — сильнее), убийство моба тира 2+
 * награждает игрока деньгами в {@code /bal}. Спавнеры, приручённые,
 * призванные и событийные мобы не тируются и не награждаются; повторная
 * выплата за того же моба невозможна (ключ награды — UUID моба).
 */
final class MobTierListener implements Listener {

    private static final int PASSIVE_DURATION = Integer.MAX_VALUE;

    private final MobsPlugin plugin;
    private final NamespacedKey tierKey;
    private final NamespacedKey eliteKey;
    private final NamespacedKey nameKey;

    MobTierListener(MobsPlugin plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "tier-v1");
        this.eliteKey = new NamespacedKey(plugin, "elite-v1");
        this.nameKey = new NamespacedKey(plugin, "name-v1");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        MobsConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy mob) || mob instanceof EnderDragon || mob instanceof Wither) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.PATROL) {
            return;
        }
        if (MobRewardPolicy.temporary(mob)) {
            return;
        }
        int tier = MobTierMath.tierFor(distanceFromSpawn(mob), config.thresholds());
        boolean tiered = tier >= 2;
        // Прокачка: снапшот очков прогресса ближайшего игрока при появлении.
        int metric = config.progression().enabled()
                ? SkillsHook.progressMetric(plugin.getLogger(),
                        nearestPlayer(mob, config.progression().radius()),
                        config.progression().prestigeWeight())
                : 0;
        boolean scaled = metric > 0;
        boolean elite = config.elite().chance() > 0
                && ThreadLocalRandom.current().nextDouble() < config.elite().chance();
        if (!tiered && !scaled && !elite) {
            return;
        }
        List<EliteAbilities> abilities = elite
                ? EliteAbilities.pick(ThreadLocalRandom.current(),
                        config.elite().abilitiesMin(), config.elite().abilitiesMax())
                : List.of();
        if (tiered) {
            mob.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        }
        if (elite) {
            mob.getPersistentDataContainer().set(eliteKey, PersistentDataType.STRING,
                    EliteAbilities.join(abilities));
        }
        double healthFactor = (tiered
                ? MobTierMath.healthFactor(tier, config.healthPerTier()) : 1.0)
                * (scaled ? MobTierMath.progressFactor(metric,
                        config.progression().healthPer100(), config.progression().maxBonus()) : 1.0)
                * (elite ? config.elite().healthMultiplier() : 1.0);
        double damageFactor = (tiered
                ? MobTierMath.damageFactor(tier, config.damagePerTier()) : 1.0)
                * (scaled ? MobTierMath.progressFactor(metric,
                        config.progression().damagePer100(), config.progression().maxBonus()) : 1.0)
                * (elite ? config.elite().damageMultiplier() : 1.0);
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() * healthFactor);
            mob.setHealth(health.getValue());
        }
        var damage = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() * damageFactor);
        }
        if (elite) {
            applyPassives(mob, abilities);
        }
        if (config.namePrefix() && plugin.messages() != null && (elite || tiered)) {
            // Базовое имя для награды: имя с бирки игрока, иначе русское название
            // из mob-names; после переименования getName() вернул бы префикс.
            String base = resolveBaseName(plugin.messages(), mob.getType().name(),
                    mob.hasCustomName() ? plain(mob.customName()) : null, mob.getName());
            mob.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, base);
            if (elite) {
                mob.customName(plugin.messages().get("elite-name",
                        "tier", tier, "title", epithet(config), "name", base));
            } else {
                mob.customName(plugin.messages().get("mob-name-prefix",
                        "tier", tier, "name", base));
            }
            // Имя не должно светиться сквозь блоки: показываем только при прямой видимости.
            mob.setCustomNameVisible(NameplateVisibility.visible(mob));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        MobsConfig config = plugin.config();
        if (config == null || !config.enabled() || plugin.loot() == null) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy mob)) {
            return;
        }
        Integer tier = mob.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        boolean elite = mob.getPersistentDataContainer().has(eliteKey, PersistentDataType.STRING);
        if ((tier == null || tier < 2) && !elite) {
            return;
        }
        if (MobRewardPolicy.temporary(mob)) {
            return;
        }
        Player killer = mob.getKiller();
        if (killer == null || !honest(killer)) {
            return;
        }
        int effectiveTier = tier == null ? 1 : tier;
        long reward = elite
                ? MobTierMath.eliteRewardDiamonds(effectiveTier, config.rewardPerTier(),
                        (int) config.elite().rewardTiers())
                : MobTierMath.rewardDiamonds(effectiveTier, config.rewardPerTier());
        if (reward <= 0L) {
            return;
        }
        if (plugin.loot().accept(killer, "mob-tier-" + mob.getUniqueId(), reward)
                && plugin.messages() != null) {
            String baseName = mob.getPersistentDataContainer()
                    .get(nameKey, PersistentDataType.STRING);
            killer.sendActionBar(plugin.messages().get(elite ? "elite-reward" : "mob-reward",
                    "tier", effectiveTier,
                    "mob", baseName != null ? baseName : mob.getName(),
                    "amount", reward));
        }
    }

    private boolean honest(Player player) {
        return player.isOnline() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }

    /** Ближайший игрок в радиусе (блоки) — источник снапшота прогресса. */
    private static Player nearestPlayer(LivingEntity mob, int radius) {
        var location = mob.getLocation();
        Player best = null;
        double bestDistance = radius * (double) radius;
        for (Player player : mob.getWorld().getPlayers()) {
            GameMode mode = player.getGameMode();
            if (player.isDead() || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE)) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /** Пассивные баффы элиты: постоянные эффекты из её набора способностей. */
    private static void applyPassives(LivingEntity mob, List<EliteAbilities> abilities) {
        for (EliteAbilities ability : abilities) {
            if (ability.kind() != EliteAbilities.Kind.PASSIVE) {
                continue;
            }
            PotionEffectType type = switch (ability) {
                case SPEED -> PotionEffectType.SPEED;
                case STRENGTH -> PotionEffectType.STRENGTH;
                case RESISTANCE -> PotionEffectType.RESISTANCE;
                case FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE;
                case REGENERATION -> PotionEffectType.REGENERATION;
                default -> null;
            };
            if (type != null) {
                // Скорость II; сила, сопротивление, огнестойкость и регенерация — I.
                int amplifier = ability == EliteAbilities.SPEED ? 1 : 0;
                mob.addPotionEffect(new PotionEffect(type, PASSIVE_DURATION, amplifier));
            }
        }
    }

    /** Случайный эпитет элиты из конфига. */
    private static String epithet(MobsConfig config) {
        List<String> epithets = config.elite().epithets();
        return epithets.get(ThreadLocalRandom.current().nextInt(epithets.size()));
    }

    /**
     * Базовое имя моба для подписи и наград: имя с бирки игрока, иначе русское
     * название из {@code mob-names} (messages.yml), иначе ванильное имя.
     */
    static String resolveBaseName(Messages messages, String typeKey, String customName, String fallback) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        if (messages != null && messages.has("mob-names." + typeKey)) {
            String value = messages.plain("mob-names." + typeKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    /** Имя с бирки игрока без разметки — для подстановки в шаблоны. */
    private static String plain(Component customName) {
        return PlainTextComponentSerializer.plainText().serialize(customName);
    }

    private static double distanceFromSpawn(org.bukkit.entity.Entity entity) {
        var location = entity.getLocation();
        var spawn = location.getWorld() == null ? null : location.getWorld().getSpawnLocation();
        if (spawn == null) {
            return 0.0;
        }
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}

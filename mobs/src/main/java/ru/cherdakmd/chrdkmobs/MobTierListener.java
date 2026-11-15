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
 * появлении (дальше от спавна мира — сильнее), свойства тира (здоровье,
 * урон, способности, награда и лут) берутся из таблицы конфига. Убийство
 * награждает деньгами в {@code /bal} и особым лутом: эссенциями навыков
 * для прокачки или тематическими сущностями вариантов (проклятые,
 * тлеющие, всратые, кринжовые). Спавнеры, приручённые, призванные и
 * событийные мобы не тируются и не награждаются; повторная выплата за
 * того же моба невозможна (ключ награды — UUID моба).
 */
final class MobTierListener implements Listener {

    private static final int PASSIVE_DURATION = Integer.MAX_VALUE;

    private final MobsPlugin plugin;
    private final NamespacedKey tierKey;
    private final NamespacedKey eliteKey;
    private final NamespacedKey variantKey;
    private final NamespacedKey nameKey;

    MobTierListener(MobsPlugin plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "tier-v1");
        this.eliteKey = new NamespacedKey(plugin, "elite-v1");
        this.variantKey = new NamespacedKey(plugin, "variant-v1");
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
        MobsConfig.Tier stats = config.tier(tier);
        boolean tiered = tier >= 2;
        // Прокачка: снапшот очков прогресса ближайшего игрока при появлении.
        int metric = config.progression().enabled()
                ? SkillsHook.progressMetric(plugin.getLogger(),
                        nearestPlayer(mob, config.progression().radius()),
                        config.progression().prestigeWeight())
                : 0;
        boolean scaled = metric > 0;
        // Редкий вариант (проклятый, тлеющий, всратый, кринжовый) — вместо элиты.
        MobVariant variant = config.variants().chance() > 0
                && ThreadLocalRandom.current().nextDouble() < config.variants().chance()
                ? MobVariant.values()[ThreadLocalRandom.current().nextInt(MobVariant.values().length)]
                : null;
        boolean elite = variant == null && config.elite().chance() > 0
                && ThreadLocalRandom.current().nextDouble() < config.elite().chance();
        // Тир сохраняется всегда: по нему считается лут при смерти.
        mob.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        if (!tiered && !scaled && !elite && variant == null) {
            return;
        }
        // Способности особых мобов: их число задаёт тир, тему — вариант.
        List<EliteAbilities> abilities = variant != null
                ? EliteAbilities.pickFrom(variant.pool(), ThreadLocalRandom.current(),
                        stats.abilitiesMin(), stats.abilitiesMax())
                : elite
                ? EliteAbilities.pick(ThreadLocalRandom.current(),
                        stats.abilitiesMin(), stats.abilitiesMax())
                : List.of();
        if (elite || variant != null) {
            mob.getPersistentDataContainer().set(eliteKey, PersistentDataType.STRING,
                    EliteAbilities.join(abilities));
        }
        if (variant != null) {
            mob.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, variant.id());
        }
        double specialHealth = variant != null ? config.variants().healthMultiplier()
                : elite ? config.elite().healthMultiplier() : 1.0;
        double specialDamage = variant != null ? config.variants().damageMultiplier()
                : elite ? config.elite().damageMultiplier() : 1.0;
        double healthFactor = stats.health()
                * (scaled ? MobTierMath.progressFactor(metric,
                        config.progression().healthPer100(), config.progression().maxBonus()) : 1.0)
                * specialHealth;
        double damageFactor = stats.damage()
                * (scaled ? MobTierMath.progressFactor(metric,
                        config.progression().damagePer100(), config.progression().maxBonus()) : 1.0)
                * specialDamage;
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() * healthFactor);
            mob.setHealth(health.getValue());
        }
        var damage = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() * damageFactor);
        }
        if (elite || variant != null) {
            applyPassives(mob, abilities);
        }
        if (config.namePrefix() && plugin.messages() != null && (elite || variant != null || tiered)) {
            // Базовое имя для награды: имя с бирки игрока, иначе русское название
            // из mob-names; после переименования getName() вернул бы префикс.
            Component tag = mob.customName();
            String base = resolveBaseName(plugin.messages(), mob.getType().name(),
                    tag == null ? null : plain(tag), mob.getName());
            mob.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, base);
            if (variant != null) {
                mob.customName(plugin.messages().get("elite-name",
                        "tier", tier, "title", plugin.messages().mini(variant.titleKey()), "name", base));
            } else if (elite) {
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
        if (config == null || !config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy mob) || MobRewardPolicy.temporary(mob)) {
            return;
        }
        Integer tierValue = mob.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        if (tierValue == null) {
            return;
        }
        int tier = tierValue;
        MobsConfig.Tier stats = config.tier(tier);
        boolean elite = mob.getPersistentDataContainer().has(eliteKey, PersistentDataType.STRING);
        MobVariant variant = MobVariant.byId(
                mob.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING));
        Player killer = mob.getKiller();
        if (killer == null || !honest(killer)) {
            return;
        }
        // Деньги: по таблице тира; элита и вариант платят как за тир выше
        // на reward-tiers ступеней. Прогрессией награда не масштабируется.
        if (plugin.loot() != null) {
            long reward = (elite || variant != null)
                    ? config.tier(tier + (int) config.elite().rewardTiers()).reward()
                    : stats.reward();
            if (reward > 0 && plugin.loot().accept(killer, "mob-tier-" + mob.getUniqueId(), reward)
                    && plugin.messages() != null) {
                String baseName = mob.getPersistentDataContainer()
                        .get(nameKey, PersistentDataType.STRING);
                if (variant != null) {
                    killer.sendActionBar(plugin.messages().get("variant-reward",
                            "title", plugin.messages().mini(variant.titleKey()),
                            "tier", tier,
                            "mob", baseName != null ? baseName : mob.getName(),
                            "amount", reward));
                } else {
                    killer.sendActionBar(plugin.messages().get(elite ? "elite-reward" : "mob-reward",
                            "tier", tier,
                            "mob", baseName != null ? baseName : mob.getName(),
                            "amount", reward));
                }
            }
        }
        // Лут: эссенции навыков для прокачки или тематика варианта; количество
        // задаёт тир, элита берёт сверху loot-bonus. Прогрессией не масштабируется.
        MobLootItems.Kind kind = variant != null
                ? MobLootItems.forVariant(variant) : MobLootItems.randomEssence();
        int count = MobLootItems.count(stats.lootMin(), stats.lootMax())
                + (elite ? config.elite().lootBonus() : 0);
        event.getDrops().add(plugin.lootItems().create(kind, count));
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

    /** Пассивные баффы особых мобов: постоянные эффекты из набора способностей. */
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

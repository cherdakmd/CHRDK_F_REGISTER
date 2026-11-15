package ru.cherdakmd.chrdkmobs;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Таблички с именами мобов — только при прямой видимости, и частицы у
 * особых мобов (по мотивам Infernal-Mobs). Ванильный
 * {@code setCustomNameVisible(true)} рисует имя сквозь блоки; здесь видимость
 * пересчитывается для каждого моба с именем (тиры, элиты и переименованные
 * биркой), чтобы имена не светились сквозь стены. Элиты и варианты сверх
 * того подсвечиваются частицами своей темы (выключается в конфиге).
 * Игроки и стойки для брони не трогаются.
 */
final class NameplateVisibility implements Runnable {

    /** Дальность в блоках: дальше табличка всё равно не читается. */
    private static final double VIEW_RADIUS = 64.0;

    private final MobsPlugin plugin;
    private final NamespacedKey eliteKey;
    private final NamespacedKey variantKey;

    NameplateVisibility(MobsPlugin plugin) {
        this.plugin = plugin;
        this.eliteKey = new NamespacedKey(plugin, "elite-v1");
        this.variantKey = new NamespacedKey(plugin, "variant-v1");
    }

    @Override
    public void run() {
        MobsConfig config = plugin.config();
        boolean particles = config != null && config.particles();
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity instanceof Player || entity instanceof ArmorStand) {
                    continue;
                }
                if (particles) {
                    spawnParticle(entity);
                }
                if (entity.customName() == null) {
                    continue;
                }
                boolean show = visible(entity);
                if (entity.isCustomNameVisible() != show) {
                    entity.setCustomNameVisible(show);
                }
            }
        }
    }

    /** Частицы своей темы у элит и вариантов; обычные мобы не подсвечиваются. */
    private void spawnParticle(LivingEntity entity) {
        if (!entity.getPersistentDataContainer().has(eliteKey, PersistentDataType.STRING)) {
            return;
        }
        Particle particle = themeParticle(MobVariant.byId(entity.getPersistentDataContainer()
                .get(variantKey, PersistentDataType.STRING)));
        var spot = entity.getLocation().add(0.0, 1.0, 0.0);
        entity.getWorld().spawnParticle(particle, spot, 2, 0.35, 0.5, 0.35, 0.0);
    }

    /** Тема частиц: вариант — своя, элита без варианта — потусторонний вихрь. */
    private static Particle themeParticle(MobVariant variant) {
        return switch (variant == null ? MobVariant.CURSED : variant) {
            case CURSED -> Particle.SOUL;
            case SMOLDERING -> Particle.FLAME;
            case GROSS -> Particle.WITCH;
            case CRINGE -> Particle.TOTEM;
        };
    }

    /** Видит ли моба хоть один игрок в прямой видимости (не наблюдатель и не мёртвый). */
    static boolean visible(LivingEntity entity) {
        if (!entity.isValid() || entity.isDead()) {
            return false;
        }
        var location = entity.getLocation();
        double limit = VIEW_RADIUS * VIEW_RADIUS;
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) > limit) {
                continue;
            }
            if (player.hasLineOfSight(entity)) {
                return true;
            }
        }
        return false;
    }
}

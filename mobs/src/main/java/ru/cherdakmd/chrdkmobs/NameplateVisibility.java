package ru.cherdakmd.chrdkmobs;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Таблички с именами мобов — только при прямой видимости. Ванильный
 * {@code setCustomNameVisible(true)} рисует имя сквозь блоки; здесь видимость
 * пересчитывается для каждого моба с именем (тиры, элиты и переименованные
 * биркой), чтобы имена не светились сквозь стены. Игроки и стойки для брони
 * не трогаются: их таблички — отдельная ванильная механика.
 */
final class NameplateVisibility implements Runnable {

    /** Дальность в блоках: дальше табличка всё равно не читается. */
    private static final double VIEW_RADIUS = 64.0;

    @Override
    public void run() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity instanceof Player || entity instanceof ArmorStand
                        || entity.customName() == null) {
                    continue;
                }
                boolean show = visible(entity);
                if (entity.isCustomNameVisible() != show) {
                    entity.setCustomNameVisible(show);
                }
            }
        }
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

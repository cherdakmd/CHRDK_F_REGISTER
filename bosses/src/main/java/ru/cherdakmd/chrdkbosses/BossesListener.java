package ru.cherdakmd.chrdkbosses;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * События мира для боссов и зон (1.4.162).
 *
 * <p>Босс получает урон только от игроков: лава, кактусы и своя же свита не
 * должны отбирать бой и награду. Урон босса усилен множителем текущей фазы, а
 * смерть обычного моба в зоне опасности приносит надбавку к опыту.</p>
 */
public final class BossesListener implements Listener {

    private final BossesPlugin plugin;

    BossesListener(BossesPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- урон боссу ------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageEvent event) {
        ActiveBoss boss = plugin.service().byEntity(event.getEntity().getUniqueId());
        if (boss == null) {
            return;
        }
        Player attacker = attacker(event);
        if (attacker == null) {
            // Босс не должен умирать от лавы, падения или собственной свиты.
            event.setCancelled(true);
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        plugin.service().damage(boss, attacker, event.getFinalDamage());
    }

    /** Урон босса усилен множителем фазы. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossAttack(EntityDamageByEntityEvent event) {
        ActiveBoss boss = plugin.service().byEntity(event.getDamager().getUniqueId());
        if (boss == null || !(event.getEntity() instanceof Player)) {
            return;
        }
        double multiplier = boss.phase().damageMultiplier();
        if (multiplier > 1.0) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    /* ---------------------------------- смерть -------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        ActiveBoss boss = plugin.service().byEntity(event.getEntity().getUniqueId());
        if (boss != null) {
            // Ванильный дроп убираем: награда выдаётся отдельно и делится по вкладу.
            event.getDrops().clear();
            event.setDroppedExp(0);
            plugin.service().handleDeath(boss, event.getEntity().getKiller());
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getDroppedExp() <= 0) {
            return;
        }
        int bonus = plugin.service().bonusXp(killer, event.getDroppedExp());
        if (bonus > 0) {
            killer.giveExp(bonus);
            plugin.service().showBonus(killer, bonus);
        }
    }

    /* ---------------------------------- прочее -------------------------------- */

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().forget(event.getPlayer().getUniqueId());
    }

    /**
     * Убирает осиротевших боссов: если сервер упал, тег остался, а сессии уже
     * нет, сущность нельзя оставлять в мире — она неубиваема и пугает игроков.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity.getScoreboardTags().contains(BossService.BOSS_TAG)
                    && plugin.service().byEntity(entity.getUniqueId()) == null) {
                entity.remove();
            }
        }
    }

    /** Игрок, который нанёс урон: прямой удар или выстрел. */
    private static Player attacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}

package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Честный PvE-вклад с журналом и наградами (перенос из классов, п.28):
 * реальный урон по естественным врагам копит журнал вклада ({@code pve} в
 * skills.json) и выдаёт бонусные очки общего пула — награду за вклад. Временные
 * цели и PvP не считаются, вклад по одному мобу ограничен его здоровьем.
 * Суточного лимита награды очками нет.
 */
final class PveContributionListener implements Listener {

    private static final int MAX_TRACKED_MOBS = 2_048;

    private final SkillsPlugin plugin;
    /** Моб → игрок → суммарный реальный урон. */
    private final Map<UUID, Map<UUID, Double>> hits = new HashMap<>();

    PveContributionListener(SkillsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || mob instanceof Player
                || MobRewardPolicy.temporary(mob)) {
            return;
        }
        Player player = damager(event.getDamager());
        if (player == null || mob.equals(player) || !honest(player)) {
            return;
        }
        if (hits.size() >= MAX_TRACKED_MOBS) {
            hits.clear();
        }
        hits.computeIfAbsent(mob.getUniqueId(), key -> new HashMap<>())
                .merge(player.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Map<UUID, Double> contributors = hits.remove(event.getEntity().getUniqueId());
        if (contributors == null || contributors.isEmpty()) {
            return;
        }
        double maxHealth = maxHealth(event.getEntity());
        for (Map.Entry<UUID, Double> entry : contributors.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !honest(player)) {
                continue;
            }
            long units = Math.max(1L, (long) Math.ceil(Math.min(entry.getValue(), maxHealth)));
            award(player, units);
        }
    }

    /** Вклад и очки попадают в один снимок; дневного ограничения нет. */
    private void award(Player player, long units) {
        int granted = plugin.skills().awardPve(player, units);
        if (granted <= 0) return;
        plugin.messages().send(player, "pve-award",
                "points", granted, "pve", plugin.skills().pveContribution(player));
    }

    private Player damager(org.bukkit.entity.Entity source) {
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean honest(Player player) {
        return player.isOnline() && !player.isDead()
                && (player.getGameMode() == org.bukkit.GameMode.SURVIVAL
                || player.getGameMode() == org.bukkit.GameMode.ADVENTURE)
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }

    private static double maxHealth(LivingEntity mob) {
        var attribute = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}

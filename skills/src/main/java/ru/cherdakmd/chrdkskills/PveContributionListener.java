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
 * цели и PvP не считаются, вклад по одному мобу ограничен его здоровьем,
 * награда очками — дневной потолок ({@link BranchMath#PVE_DAILY_POINTS}).
 */
final class PveContributionListener implements Listener {

    private static final long DAY_MS = 86_400_000L;
    private static final int MAX_TRACKED_MOBS = 2_048;

    private final SkillsPlugin plugin;
    /** Моб → игрок → суммарный реальный урон. */
    private final Map<UUID, Map<UUID, Double>> hits = new HashMap<>();
    /** Игрок → [календарный день, выданные сегодня очки]. */
    private final Map<UUID, int[]> daily = new HashMap<>();

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

    /** Вклад идёт в журнал всегда; очки — только в пределах дневного потолка. */
    private void award(Player player, long units) {
        int due = plugin.skills().addPve(player, units);
        if (due <= 0) {
            return;
        }
        int granted = Math.min(due, dailyAllowance(player));
        if (granted <= 0) {
            return;
        }
        plugin.skills().grantBonusPoints(player, granted);
        recordDaily(player, granted);
        plugin.messages().send(player, "pve-award",
                "points", granted, "pve", plugin.skills().pveContribution(player));
    }

    private int dailyAllowance(Player player) {
        int day = (int) (System.currentTimeMillis() / DAY_MS);
        int[] slot = daily.computeIfAbsent(player.getUniqueId(), key -> new int[]{day, 0});
        if (slot[0] != day) {
            slot[0] = day;
            slot[1] = 0;
        }
        return Math.max(0, BranchMath.PVE_DAILY_POINTS - slot[1]);
    }

    private void recordDaily(Player player, int granted) {
        int day = (int) (System.currentTimeMillis() / DAY_MS);
        int[] slot = daily.computeIfAbsent(player.getUniqueId(), key -> new int[]{day, 0});
        if (slot[0] != day) {
            slot[0] = day;
            slot[1] = 0;
        }
        slot[1] += granted;
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

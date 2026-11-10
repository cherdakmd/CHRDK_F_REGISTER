package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Начисление осколков и опыта стойкости за пережитую катастрофу. Участие —
 * игрок в радиусе события на момент завершения сценария; право
 * {@code chrdkdisasters.bypass} награды не получает, покупной пропуск защищает
 * только от собственного расписания.
 *
 * <p>Всё начисление идёт через {@link DisasterProfile#earn} с дневным лимитом,
 * поэтому катастрофы нельзя превратить в ферму алмазов. Интеграции с
 * CHRDK_Achievements и CHRDK_Skills мягкие и живут в отдельных хуках.</p>
 */
public final class DisasterRewards {

    private final DisastersPlugin plugin;

    public DisasterRewards(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Уровень стойкости игрока по его профилю (1, если адрес недоступен). */
    public int levelOf(Player player) {
        if (player == null) {
            return 1;
        }
        DisasterSettings.Progression progression = plugin.settings().progression();
        return DisasterProgressMath.levelForXp(
                plugin.progress().profile(player.getUniqueId()).xp(),
                progression.baseXp(), progression.maxLevel());
    }

    public int levelOf(String playerName) {
        return playerName == null ? 1 : levelOf(Bukkit.getPlayerExact(playerName));
    }

    public void grant(DisasterRun run, Location center) {
        DisastersConfig config = plugin.settings();
        DisasterSettings.Rewards rewards = config.rewards();
        if (!rewards.enabled() || center == null || center.getWorld() == null) {
            return;
        }
        DisasterSettings.Progression progression = config.progression();
        DisasterSettings.Integrations integrations = config.integrations();
        long now = System.currentTimeMillis();
        String day = DisasterProgressMath.dayKey(now);
        double limit = (double) config.radius() * config.radius();
        for (Player player : center.getWorld().getPlayers()) {
            if (player.hasPermission("chrdkdisasters.bypass")
                    || player.getWorld() != center.getWorld()
                    || player.getLocation().distanceSquared(center) > limit) {
                continue;
            }
            DisasterProfile profile = plugin.progress().profile(player.getUniqueId());
            int before = DisasterProgressMath.levelForXp(profile.xp(),
                    progression.baseXp(), progression.maxLevel());
            long reward = DisasterProgressMath.shardReward(run.level(), before,
                    rewards.base(), rewards.perLevel(), rewards.bonusCap());
            long granted = profile.earn(reward, rewards.dailyCap(), day);
            profile.addXp(progression.xpPerSurvive()
                    + (long) run.level() * progression.xpPerLevel());
            boolean newKind = profile.addKind(run.kind().id());
            int after = DisasterProgressMath.levelForXp(profile.xp(),
                    progression.baseXp(), progression.maxLevel());

            if (integrations.achievements()) {
                AchievementsHook.progress(plugin.getLogger(), player,
                        "disasters-survived", 1L);
                if (granted > 0L) {
                    AchievementsHook.progress(plugin.getLogger(), player,
                            "disaster-shards", granted);
                }
                if (newKind) {
                    AchievementsHook.progress(plugin.getLogger(), player,
                            "disaster-kinds", 1L);
                }
                if (after > before) {
                    AchievementsHook.progress(plugin.getLogger(), player,
                            "disaster-level", after - before);
                }
            }
            if (integrations.skills()) {
                SkillsHook.award(plugin.getLogger(), player, integrations.skillId(),
                        integrations.units() + run.level());
            }
            plugin.messages().send(player, "reward",
                    "kind", plugin.messages().raw(run.kind().titleKey()),
                    "shards", granted, "level", after,
                    "xp", progression.xpPerSurvive()
                            + (long) run.level() * progression.xpPerLevel());
        }
        plugin.progress().saveNow();
    }
}

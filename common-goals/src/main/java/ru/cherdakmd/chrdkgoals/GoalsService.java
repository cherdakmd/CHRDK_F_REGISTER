package ru.cherdakmd.chrdkgoals;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Обработчик прогресса: фильтрация (авторизация, активная волна), добавление вклада,
 * замечание завершения цели (рассылка + раздача пула участникам), ежеминутный snapshot.
 */
public final class GoalsService {

    private final CommonGoalsPlugin plugin;
    private final java.util.Random random = new java.util.Random();

    public GoalsService(CommonGoalsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Засчитывает прогресс «сырым» событием от слушателей. Проверки — на вызывающем. */
    public void addProgress(GoalMetric metric, Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        if (plugin.config().requireAuth()
                && (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player))) {
            return;
        }
        for (GoalState goal : plugin.store().goals()) {
            GoalTemplate template = GoalTemplate.byId(goal.id);
            if (template == null || template.metric() != metric || goal.done) {
                continue;
            }
            boolean completed = plugin.store().addProgress(goal.id, player.getName(), amount);
            if (completed) {
                onGoalCompleted(goal);
            }
        }
    }

    /** Завершение цели: рассылка всем + распределение пула в pending-награды. */
    private void onGoalCompleted(GoalState goal) {
        GoalTemplate template = GoalTemplate.byId(goal.id);
        java.util.Map<String, Integer> shares = GoalRewards.distribute(
                goal.rewardPool, goal.contributions, plugin.config().rewardBonusPercent());
        for (java.util.Map.Entry<String, Integer> entry : shares.entrySet()) {
            plugin.store().addPendingReward(entry.getKey(), entry.getValue());
            Player online = Bukkit.getPlayerExact(entry.getKey());
            if (online != null) {
                plugin.messages().send(online, "goal-complete-you",
                        "goal", goalName(template),
                        "reward", String.valueOf(entry.getValue()));
            }
        }
        String goalName = goalName(template);
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.messages().send(player, "goal-completed",
                    "goal", goalName,
                    "pool", String.valueOf(goal.rewardPool),
                    "heroes", String.valueOf(shares.size()));
        }
        // если все цели волны выполнены — внеплановая ротация через короткий промежуток
        boolean allDone = plugin.store().goals().stream().allMatch(g -> g.done);
        if (allDone) {
            plugin.rotate(6000); // через 5 минут (в тиках)
        }
    }

    public String goalName(GoalTemplate template) {
        return plugin.messages().raw("goal." + template.id() + ".name");
    }
}

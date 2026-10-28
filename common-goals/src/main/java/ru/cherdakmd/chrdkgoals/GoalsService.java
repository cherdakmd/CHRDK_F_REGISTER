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
            try {
                boolean completed = plugin.store().progressAndRewards(goal.id, player.getName(), amount, plugin.config().rewardBonusPercent());
                if (completed) { onGoalCompleted(plugin.store().goal(goal.id).orElseThrow()); settleRewards(); }
            } catch (java.io.IOException | RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Не удалось сохранить прогресс/доход целей; выплаты приостановлены", error);
                return;
            }
        }
    }

    /** Завершение цели: рассылка всем + распределение пула в pending-награды. */
    private void onGoalCompleted(GoalState goal) {
        GoalTemplate template = GoalTemplate.byId(goal.id);
        java.util.Map<String, Integer> shares = GoalRewards.distribute(
                goal.rewardPool, goal.contributions, plugin.config().rewardBonusPercent());
        for (java.util.Map.Entry<String, Integer> entry : shares.entrySet()) {
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

    private boolean ensureIncome() {
        if (!plugin.store().healthy() || plugin.coreApi() == null || !plugin.coreApi().personalBankAvailable()) return false;
        if (plugin.store().bankRegistered()) return plugin.coreApi().incomeSourceAvailable("common-goals", plugin.store().incomeId());
        if (!plugin.coreApi().registerIncomeSource("common-goals", plugin.store().incomeId())) return false;
        try { plugin.store().markBankRegistered(); return true; }
        catch (java.io.IOException error) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Не записана регистрация доходов целей", error); return false; }
    }
    public void settleRewards() {
        if (!ensureIncome()) return;
        try { plugin.store().saveStrict(); }
        catch (java.io.IOException error) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Источник доходов целей не сохранён", error); return; }
        for (var row : plugin.store().entitlements().entrySet()) {
            var owner = plugin.coreApi().knownBankPlayer(row.getKey());
            if (owner != null) plugin.coreApi().creditEntitlement("common-goals", plugin.store().incomeId(), row.getKey(), owner, row.getValue());
        }
    }
    public long awaiting(Player player) {
        if (!ensureIncome()) return -1;
        long total = plugin.store().entitlementOf(player.getName());
        long credited = plugin.coreApi().creditedEntitlement("common-goals", plugin.store().incomeId(), player.getName().toLowerCase(java.util.Locale.ROOT), player.getUniqueId());
        return credited < 0 || credited > total ? -1 : total - credited;
    }
    public void claimRewards(Player player) {
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        long total = plugin.store().entitlementOf(player.getName());
        if (total <= 0) { plugin.messages().send(player, "claim-none"); return; }
        try {
            if (!ensureIncome()) { plugin.messages().send(player, "bank-wait"); return; }
            plugin.store().saveStrict();
            String name = player.getName().toLowerCase(java.util.Locale.ROOT);
            // UUID авторизованного владельца, не вычисленный offline UUID по нику.
            if (!plugin.coreApi().creditEntitlement("common-goals", plugin.store().incomeId(), name, player.getUniqueId(), total)) {
                plugin.messages().send(player, "bank-wait"); return;
            }
            plugin.messages().send(player, "bank-paid", "balance", plugin.coreApi().auctionBankBalance(player.getUniqueId()));
        } catch (java.io.IOException error) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Не сохранён источник награды", error); plugin.messages().send(player, "bank-wait"); }
    }

    public String goalName(GoalTemplate template) {
        return plugin.messages().raw("goal." + template.id() + ".name");
    }
}

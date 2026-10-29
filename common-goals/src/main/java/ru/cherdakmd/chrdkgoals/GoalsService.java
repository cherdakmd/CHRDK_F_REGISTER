package ru.cherdakmd.chrdkgoals;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.*;

/**
 * Обработчик прогресса: фильтрация (авторизация, активная волна), добавление вклада,
 * замечание завершения цели (рассылка + раздача пула участникам), ежеминутный snapshot.
 */
public final class GoalsService {

    private final CommonGoalsPlugin plugin;
    private ChargeJournal charges;
    private final java.util.Random random = new java.util.Random();

    public GoalsService(CommonGoalsPlugin plugin) {
        this.plugin = plugin;
    }

    public void startPayments() throws java.io.IOException {
        charges = new ChargeJournal(plugin.getDataFolder().toPath(), "common-goals", plugin.store().incomeId());
        plugin.store().bindFinance(this::paymentsBusy);
        if (charges.pending() != null && !ChargeInventory.valid(charges.pending())) throw new java.io.IOException("Неверный план взноса целей");
        recoverCharge(null);
    }
    public boolean paymentsBusy() { return !plugin.store().healthy() || charges != null && (!charges.healthy() || charges.pending() != null); }
    private void lock(Player p) { p.kick(net.kyori.adventure.text.Component.text("Общие цели: незавершённая предметная оплата. Повторите вход после проверки банка; при конфликте нужен администратор.")); }
    private boolean recoverCharge(Player p) {
        if (charges == null || charges.pending() == null) return !paymentsBusy();
        var pending = charges.pending();
        try {
            var decision = ChargeInventory.recover(charges, plugin.coreApi(), p, plugin.store());
            if (decision == ChargeRecovery.Decision.WAIT || decision == ChargeRecovery.Decision.CONFLICT) {
                if (p != null && p.getUniqueId().equals(pending.payment().payer()) && pending.payment().cash() > 0) lock(p);
                return false;
            }
            plugin.getLogger().info("Взнос целей восстановлен: " + pending.payment().transaction() + " / " + decision);
            if (decision != ChargeRecovery.Decision.ROLLBACK && !plugin.store().goals().isEmpty() && plugin.store().goals().stream().allMatch(g -> g.done)) plugin.rotate(6000);
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Взнос целей не восстановлен; журнал сохранён", error);
            if (p != null && p.getUniqueId().equals(pending.payment().payer()) && pending.payment().cash() > 0) lock(p);
            return false;
        }
    }
    public boolean recoverPending() {
        var pending = charges == null ? null : charges.pending();
        return pending == null ? !paymentsBusy() : recoverCharge(pending.payment().cash() == 0 ? null : Bukkit.getPlayer(pending.payment().payer()));
    }
    public boolean onAuthPayment(Player p) {
        var pending = charges == null ? null : charges.pending();
        return pending == null || !pending.payment().payer().equals(p.getUniqueId()) || recoverCharge(p);
    }
    public void donate(Player p, int amount) {
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(p) || !p.hasPermission("chrdkgoals.use")
                || p.hasMetadata("NPC") || p.isDead()
                || p.getGameMode() != org.bukkit.GameMode.SURVIVAL && p.getGameMode() != org.bukkit.GameMode.ADVENTURE) {
            plugin.messages().send(p, "bank-mode"); return;
        }
        if (paymentsBusy()) { recoverCharge(p); if (paymentsBusy()) { plugin.messages().send(p, "bank-recovery"); return; } }
        if (!ensureIncome()) { plugin.messages().send(p, "bank-wait"); return; }
        if (amount < 1 || amount > 100_000) { plugin.messages().send(p, "donate-invalid", "max", "100000"); return; }
        var goal = plugin.store().goal("donate-diamonds").filter(g -> !g.done).orElse(null);
        if (goal == null) { plugin.messages().send(p, "donate-no-goal"); return; }
        var before = ChargeInventory.copy(p.getInventory().getStorageContents());
        var plan = ChargeInventory.plan(before, amount, plugin.coreApi().auctionBankBalance(p.getUniqueId()));
        if (plan == null) { plugin.messages().send(p, "bank-not-enough", "amount", amount); return; }
        try {
            String afterState = plugin.store().previewDonation(p.getName().toLowerCase(java.util.Locale.ROOT), amount, plugin.config().rewardBonusPercent());
            var payment = charges.prepare(p.getUniqueId(), amount, plan.debit(), plan.cash(), plan.change(),
                    ChargeInventory.encode(before), ChargeInventory.encode(plan.after()), plugin.store().snapshot(), afterState);
            plugin.coreApi().commitBankCharge(p, payment, before);
            var decision = ChargeInventory.recover(charges, plugin.coreApi(), p, plugin.store());
            if (decision == ChargeRecovery.Decision.ROLLBACK) { plugin.messages().send(p, "bank-refused"); return; }
            if (decision == ChargeRecovery.Decision.WAIT || decision == ChargeRecovery.Decision.CONFLICT) {
                if (plan.cash() > 0) lock(p); plugin.messages().send(p, "bank-recovery"); return;
            }
            plugin.messages().send(p, "bank-donated", "amount", amount, "change", plan.change());
            var completed = plugin.store().goal("donate-diamonds").orElseThrow();
            if (completed.done) onGoalCompleted(completed);
            settleRewards();
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Взнос целей остановлен; журнал сохранён", error);
            if (!charges.healthy() || !plugin.store().healthy() || charges.pending() != null && charges.pending().payment().cash() > 0) lock(p);
            plugin.messages().send(p, "bank-recovery");
        }
    }

    /** Засчитывает прогресс «сырым» событием от слушателей. Проверки — на вызывающем. */
    public void addProgress(GoalMetric metric, Player player, int amount) {
        if (paymentsBusy() || amount <= 0) {
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
        if (paymentsBusy() || !plugin.store().healthy() || plugin.coreApi() == null || !plugin.coreApi().personalBankAvailable()) return false;
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

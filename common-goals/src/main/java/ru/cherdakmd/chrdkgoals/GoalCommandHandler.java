package ru.cherdakmd.chrdkgoals;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /goals} — список активных целей (прогресс, вклад, лидер), {@code /goals claim},
 * {@code /goals donate <n>}, {@code /goals top}; {@code /goalsadmin} — rotate/reload/progress.
 */
public final class GoalCommandHandler implements CommandExecutor, TabCompleter {

    private final CommonGoalsPlugin plugin;

    public GoalCommandHandler(CommonGoalsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("goalsadmin")) {
            return handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(sender, "need-auth");
            return true;
        }
        if (args.length == 0) {
            showList(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "claim" -> claim(player);
            case "donate" -> donate(player, args);
            case "top" -> showTop(player);
            default -> plugin.messages().send(player, "usage");
        }
        return true;
    }

    /* ------------------------------ /goals ------------------------------ */

    private void showList(Player player) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("d MMM HH:mm");
        fmt.setTimeZone(java.util.TimeZone.getDefault());
        String until = fmt.format(new java.util.Date(plugin.store().rotationEndsAt()));
        plugin.messages().send(player, "list-header", "until", until);
        java.util.List<GoalState> goals = plugin.store().goals();
        if (goals.isEmpty()) {
            plugin.messages().send(player, "all-done-idle");
        }
        for (GoalState goal : goals) {
            GoalTemplate template = GoalTemplate.byId(goal.id);
            String name = template == null ? goal.id : plugin.service().goalName(template);
            String mine = String.valueOf(goal.contributionOf(player.getName()));
            if (goal.done) {
                plugin.messages().send(player, "list-line-done",
                        "name", name, "mine", mine);
            } else {
                plugin.messages().send(player, "list-line-active",
                        "name", name,
                        "progress", String.valueOf(goal.progress),
                        "target", String.valueOf(goal.target),
                        "percent", String.valueOf(goal.percent()),
                        "mine", mine,
                        "leader", goal.leader());
            }
        }
        long awaiting = plugin.service().awaiting(player);
        plugin.messages().send(player, "bank-list-footer", "pending", awaiting < 0 ? "проверка недоступна" : String.valueOf(awaiting));
    }

    private void showTop(Player player) {
        plugin.messages().send(player, "top-header");
        boolean any = false;
        for (GoalState goal : plugin.store().goals()) {
            List<Map.Entry<String, Integer>> rows = new ArrayList<>(goal.contributions.entrySet());
            if (rows.isEmpty()) {
                continue;
            }
            any = true;
            rows.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
            GoalTemplate template = GoalTemplate.byId(goal.id);
            String name = template == null ? goal.id : plugin.service().goalName(template);
            plugin.messages().send(player, "top-line-header", "name", name);
            int place = 1;
            int total = goal.contributions.values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<String, Integer> row : rows.stream().limit(5).toList()) {
                int percent = total <= 0 ? 0 : (int) Math.round(row.getValue() * 100.0 / total);
                plugin.messages().send(player, "hero-line",
                        "place", String.valueOf(place++),
                        "name", row.getKey(),
                        "contrib", String.valueOf(row.getValue()),
                        "percent", String.valueOf(percent));
            }
        }
        if (!any) {
            plugin.messages().send(player, "top-none");
        }
    }

    private void donate(Player player, String[] args) {
        int amount;
        try { amount = args.length == 2 ? Integer.parseInt(args[1]) : 0; }
        catch (NumberFormatException error) { amount = 0; }
        plugin.service().donate(player, amount);
    }

    private void claim(Player player) { plugin.service().claimRewards(player); }

    /* ------------------------------ /goalsadmin ------------------------------ */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkgoals.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("recover")) {
            plugin.messages().send(sender, plugin.service().recoverPending() ? "bank-recovered" : "bank-recovery"); return true;
        }
        if (plugin.service().paymentsBusy()) { plugin.messages().send(sender, "bank-recovery"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("rotate")) {
            plugin.rotateNow();
            plugin.messages().send(sender, "admin-rotated");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadGoalsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("progress")) {
            GoalState goal = plugin.store().goals().stream()
                    .filter(g -> g.id.equals(args[1]))
                    .findFirst().orElse(null);
            if (goal == null) {
                plugin.messages().send(sender, "admin-unknown-goal");
                return true;
            }
            int value = 0;
            try {
                value = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
                // останется 0
            }
            plugin.store().setProgress(goal.id, value);
            plugin.messages().send(sender, "admin-progress-set",
                    "goal", goal.id, "progress", String.valueOf(value));
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("goals") && args.length == 1) {
            return filter(List.of("claim", "donate", "top"), args[0]);
        }
        if (command.getName().equalsIgnoreCase("goalsadmin")) {
            if (args.length == 1) {
                return filter(List.of("rotate", "reload", "progress", "recover"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("progress")) {
                return filter(plugin.store().goals().stream().map(g -> g.id).toList(), args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(low)).toList();
    }
}

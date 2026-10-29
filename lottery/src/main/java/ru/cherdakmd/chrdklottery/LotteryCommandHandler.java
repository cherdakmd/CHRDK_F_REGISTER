package ru.cherdakmd.chrdklottery;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public final class LotteryCommandHandler implements CommandExecutor, TabCompleter {
    private final LotteryPlugin plugin;
    private final LotteryService service;
    public LotteryCommandHandler(LotteryPlugin plugin, LotteryService service) { this.plugin = plugin; this.service = service; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("lotteryadmin")) {
            if (!sender.hasPermission("chrdklottery.admin")) { plugin.messages().send(sender, "lottery.admin-no-permission"); return true; }
            if (sub.equals("reload")) {
                try { plugin.reloadLotteryConfig(); plugin.messages().send(sender, "lottery.admin-reload"); }
                catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Lottery reload failed", e); plugin.messages().send(sender, "lottery.storage-error"); }
            } else if (sub.equals("recover")) {
                plugin.messages().send(sender, service.recoverPending() ? "lottery.bank-recovered" : "lottery.bank-recovery");
            } else if (sub.equals("drawnow") && args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
                plugin.messages().send(sender, service.drawNow() ? "lottery.admin-drawnow-done" : "lottery.storage-error");
            } else plugin.messages().send(sender, "lottery.admin-usage");
            return true;
        }
        if (!sender.hasPermission("chrdklottery.use")) { plugin.messages().send(sender, "lottery.no-permission"); return true; }
        if (!(sender instanceof Player player)) { plugin.messages().send(sender, "lottery.player-only"); return true; }
        if (!service.authenticated(player)) return true;
        switch (sub) {
            case "info" -> service.sendInfo(player);
            case "top" -> service.sendTop(player);
            case "last" -> service.sendLast(player);
            case "claim" -> service.claimPrize(player);
            case "buy" -> {
                if (args.length > 2) { plugin.messages().send(player, "lottery.usage"); break; }
                try { service.buyTickets(player, args.length == 1 ? 1 : Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { plugin.messages().send(player, "lottery.buy-not-a-number"); }
            }
            default -> plugin.messages().send(player, "lottery.usage");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("lotteryadmin");
        if (!sender.hasPermission(admin ? "chrdklottery.admin" : "chrdklottery.use")) return List.of();
        List<String> options = List.of();
        if (args.length == 1) options = admin ? List.of("reload", "drawnow", "recover") : List.of("buy", "info", "top", "last", "claim");
        else if (args.length == 2 && admin && args[0].equalsIgnoreCase("drawnow")) options = List.of("confirm");
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}

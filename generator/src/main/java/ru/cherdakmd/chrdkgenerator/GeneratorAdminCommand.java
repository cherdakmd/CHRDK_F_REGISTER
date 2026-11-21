package ru.cherdakmd.chrdkgenerator;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** Администрирование генератора: reload и безопасная диагностика платежей. */
public final class GeneratorAdminCommand implements CommandExecutor, TabCompleter {

    private final GeneratorPlugin plugin;

    public GeneratorAdminCommand(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chrdkgenerator.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (paymentCommand(args)) {
            if (plugin.payments() == null) {
                plugin.messages().send(sender, "finance.service-wait");
                return true;
            }
            return plugin.payments().admin(sender, args);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.messages().send(sender, "reloaded");
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    static boolean paymentCommand(String[] args) {
        return args != null && args.length == 1 && (args[0].equalsIgnoreCase("finance")
                || args[0].equalsIgnoreCase("recover"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "finance", "recover").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList() : List.of();
    }
}

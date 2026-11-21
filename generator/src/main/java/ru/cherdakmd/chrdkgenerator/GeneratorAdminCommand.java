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
        if (args.length > 0 && args[0].equalsIgnoreCase("rebase-lost")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                sender.sendMessage("Согласование утраченной истории доступно только из консоли.");
                return true;
            }
            if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                sender.sendMessage("Использование: generatoradmin rebase-lost <UUID платежа из finance> confirm. Исход платежа неизвестен; услуга и возврат не выполняются.");
                return true;
            }
            try {
                var id = java.util.UUID.fromString(args[1]);
                if (plugin.payments() != null && plugin.payments().rebaseLostGeneratorHistory(id, sender.getName())) {
                    sender.sendMessage("Журнал согласован, прежнее намерение сохранено в архив. Проверьте generatoradmin finance. Балансы не изменены.");
                } else {
                    sender.sendMessage("Согласование отклонено: условия безопасности не выполнены. Данные не изменены.");
                }
            } catch (IllegalArgumentException error) {
                sender.sendMessage("Неверный UUID или небезопасное состояние. Согласование не выполнено.");
            } catch (Exception error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка согласования журнала генератора; проверьте архив и журнал перед повтором", error);
                sender.sendMessage("Ошибка записи: исход согласования требует проверки файлов и лога; не повторяйте вслепую.");
            }
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

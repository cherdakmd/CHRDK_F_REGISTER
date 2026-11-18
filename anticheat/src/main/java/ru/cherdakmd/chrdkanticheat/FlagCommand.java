package ru.cherdakmd.chrdkanticheat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;

/**
 * Служебная команда моста {@code chrdkac flag <игрок> <чек> <vl> [verbose…]}.
 *
 * <p>Её исполняет детектор (Grim) из консоли через свой punishments.yml.
 * Поверх блокировки GuardPolicy команда сама отвергает игроков — защита в
 * глубину: даже компрометация OP не должна подделать журнал из игры.</p>
 */
final class FlagCommand implements CommandExecutor {

    private final AnticheatPlugin plugin;

    FlagCommand(AnticheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            plugin.messages().send(sender, "console-only");
            return true;
        }
        if (!plugin.settings().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        FlagIngest.Parsed parsed;
        try {
            parsed = FlagIngest.parse(args);
        } catch (IllegalArgumentException error) {
            plugin.messages().send(sender, "chrdkac-usage");
            return true;
        }
        Player online = Bukkit.getPlayerExact(parsed.player());
        FlagRecord draft = new FlagRecord(
                0,
                System.currentTimeMillis(),
                parsed.player(),
                online == null ? "" : online.getUniqueId().toString(),
                parsed.check(),
                parsed.vl(),
                online == null ? -1 : online.getPing(),
                Bukkit.getTPS().length == 0 ? 0 : Bukkit.getTPS()[0],
                online == null ? "" : online.getWorld().getName(),
                online == null ? 0 : online.getLocation().getX(),
                online == null ? 0 : online.getLocation().getY(),
                online == null ? 0 : online.getLocation().getZ(),
                parsed.verbose());
        try {
            plugin.onFlag(plugin.journal().append(draft));
        } catch (IOException error) {
            plugin.getLogger().warning("Журнал античита не принял запись: " + error.getMessage());
        }
        return true;
    }
}

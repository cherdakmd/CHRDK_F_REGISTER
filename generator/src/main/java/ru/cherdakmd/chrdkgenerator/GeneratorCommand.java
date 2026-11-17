package ru.cherdakmd.chrdkgenerator;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** Команда /generator (alias /gen): статус личного генератора. */
public final class GeneratorCommand implements CommandExecutor, TabCompleter {

    private final GeneratorPlugin plugin;

    public GeneratorCommand(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkgenerator.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        // Скелет: механика мира и GUI появятся следующими шагами (п.31).
        plugin.messages().send(player, "not-ready");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}

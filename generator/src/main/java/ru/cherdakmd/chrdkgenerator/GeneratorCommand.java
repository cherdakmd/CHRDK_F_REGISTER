package ru.cherdakmd.chrdkgenerator;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.List;

/** Команда /generator (alias /gen): статус личного генератора, снятие бункера. */
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
        if (args.length > 0 && "take".equalsIgnoreCase(args[0])) {
            // Снятие с подтверждением — шаг 5 (EconomyConfirmations TTL 30s).
            plugin.messages().send(player, "take-not-ready");
            return true;
        }
        if (args.length > 0) {
            plugin.messages().send(player, "usage");
            return true;
        }
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null) {
            plugin.messages().send(player, "status-none");
            return true;
        }
        if (!entry.installed()) {
            plugin.messages().send(player, "status-carried");
            return true;
        }
        String state = "state-working";
        World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            state = "state-unloaded";
        } else {
            state = GeneratorCells.stateKey(GeneratorChamber.scan(
                    GeneratorCells.scan(world, entry.x(), entry.y(), entry.z())).state());
        }
        long capacity = (long) GeneratorMath.bunkerCapacity(plugin.config().bunkerBase(),
                entry.modules().getOrDefault("capacity", 0)) * plugin.config().offlineHours();
        int speed = entry.modules().getOrDefault("speed", 0);
        Component line = plugin.messages().get("status-line",
                "tier", String.valueOf(entry.tier()),
                "tier_name", plugin.config().tier(entry.tier()).name(),
                "bunker", String.valueOf(entry.bunker()),
                "capacity", String.valueOf(capacity),
                "rate", String.valueOf(GeneratorMath.cycleSeconds(plugin.config().cycleSeconds(), speed)),
                "state", Messages.trusted(plugin.messages().raw(state)));
        player.sendMessage(line);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("chrdkgenerator.use")) {
            return List.of("take").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}

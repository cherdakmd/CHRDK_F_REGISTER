package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** /disasters — статус игрока; /disastersadmin — управление. */
public final class DisasterCommandHandler implements CommandExecutor {

    private final DisastersPlugin plugin;

    public DisasterCommandHandler(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("disastersadmin");
        if (admin && !sender.hasPermission("chrdkdisasters.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!admin) {
            playerStatus(sender);
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender, args);
            case "stop" -> {
                plugin.engine().stop();
                plugin.restore().restoreAll();
                plugin.engine().start();
                plugin.messages().send(sender, "admin-stopped");
            }
            case "restore" -> plugin.messages().send(sender, "admin-restored",
                    "count", plugin.restore().restoreAll());
            case "reload" -> {
                plugin.reloadAll();
                plugin.messages().send(sender, "reloaded");
            }
            case "on" -> {
                plugin.setRuntimeEnabled(true);
                plugin.messages().send(sender, "admin-on");
            }
            case "off" -> {
                plugin.setRuntimeEnabled(false);
                plugin.messages().send(sender, "admin-off");
            }
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void playerStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (!plugin.settings().enabled() || !plugin.runtimeEnabled()) {
            plugin.messages().send(sender, "status-disabled");
            return;
        }
        long left = Math.max(0L,
                plugin.scheduler().nextFor(player.getName(), System.currentTimeMillis())
                        - System.currentTimeMillis());
        plugin.messages().send(sender, "status-enabled", "seconds", left / 1000L);
    }

    private void start(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        var kind = DisasterKind.fromId(args[1]);
        if (kind.isEmpty() || !plugin.settings().enabledKinds().contains(kind.get())) {
            plugin.messages().send(sender, "unknown-kind");
            return;
        }
        int level = 1;
        if (args.length >= 3) {
            try {
                level = Math.clamp(Integer.parseInt(args[2]), 1, plugin.settings().maxLevel());
            } catch (NumberFormatException ignored) {
                level = 1;
            }
        }
        Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : null;
        if (target == null && sender instanceof Player self) {
            target = self;
        }
        if (target == null) {
            plugin.messages().send(sender, "admin-not-found");
            return;
        }
        boolean ok = plugin.engine().runNow(kind.get(), level, target.getLocation(), target);
        plugin.messages().send(sender, ok ? "admin-started" : "admin-blocked",
                "kind", kind.get().id(), "level", level, "player", target.getName());
    }
}

package ru.cherdakmd.chrdkqueue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** /queue <info | clear | reload>. */
public final class QueueCommandHandler implements CommandExecutor, TabCompleter {

    private final QueuePlugin plugin;

    public QueueCommandHandler(QueuePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        QueueConfig cfg = plugin.queueService().config();
        String sub = args.length == 0 ? "info" : args[0].trim().toLowerCase(java.util.Locale.ROOT);

        if (!sender.hasPermission("chrdkqueue.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }

        switch (sub) {
            case "info" -> {
                List<QueueService.Entry> snapshot = plugin.queueService().snapshot();
                plugin.messages().send(sender, "info-summary",
                        "online", Bukkit.getOnlinePlayers().size(),
                        "limit", cfg.regularLimit(),
                        "groups", cfg.vipGroups().isEmpty() ? "-" : String.join(", ", cfg.vipGroups()),
                        "reserve", cfg.reservationSeconds());
                if (snapshot.isEmpty()) {
                    plugin.messages().send(sender, "info-empty");
                    return true;
                }
                plugin.messages().send(sender, "info-header", "size", snapshot.size());
                long now = System.currentTimeMillis();
                int position = 1;
                for (QueueService.Entry entry : snapshot) {
                    long waitMinutes = Math.max(0L, (now - entry.lastSeenMillis()) / 60_000L);
                    plugin.messages().send(sender, "info-entry",
                            "position", position++,
                            "name", entry.name(),
                            "wait", waitMinutes,
                            "reserved", plugin.queueService().isReservedFor(entry.name())
                                    ? plugin.messages().mini("info-reserved-tag") : "");
                }
                return true;
            }
            case "clear" -> {
                if (!sender.hasPermission("chrdkqueue.admin")) {
                    plugin.messages().send(sender, "no-permission");
                    return true;
                }
                int before = plugin.queueService().clear();
                plugin.messages().send(sender, "cleared");
                plugin.getLogger().info("[Queue] Очередь очищена игроком " + sender.getName()
                        + " (было " + before + " записей).");
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("chrdkqueue.admin")) {
                    plugin.messages().send(sender, "no-permission");
                    return true;
                }
                plugin.reloadQueue();
                plugin.messages().send(sender, "reloaded");
                return true;
            }
            default -> {
                plugin.messages().send(sender, "usage", "label", label);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of("info", "clear", "reload")
                .filter(sub -> sub.startsWith(prefix))
                .filter(sub -> "info".equals(sub) || sender.hasPermission("chrdkqueue.admin"))
                .toList();
    }
}

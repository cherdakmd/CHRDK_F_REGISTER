package ru.cherdakmd.chrdkachievements;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /achievements} — витрина, список и бейджи; {@code /achadmin} —
 * перезагрузка, выдача и сброс.
 *
 * <p>Команда для игроков не требует прав кроме {@code chrdkachievements.use};
 * список доступен и в консоли (без бейджей). Админские операции пишут в лог
 * источник выдачи — это важно для разбора «откуда у игрока награда».</p>
 */
public final class AchievementCommandHandler implements CommandExecutor, TabCompleter {

    private final AchievementsPlugin plugin;
    private final AchievementService service;
    private final AchievementGui gui;

    public AchievementCommandHandler(AchievementsPlugin plugin, AchievementService service,
                                     AchievementGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("achievements")) {
            return handlePlayer(sender, args);
        }
        if (command.getName().equalsIgnoreCase("achadmin")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkachievements.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            gui.open(player, null);
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            return handleList(sender);
        }
        if (args[0].equalsIgnoreCase("badge")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            return handleBadge(player, args);
        }
        plugin.messages().send(sender, "usage");
        return true;
    }

    /** Текстовый список: по разделам, с прогрессом и статусом. */
    private boolean handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!service.enabled()) {
            plugin.messages().send(player, "achievements-disabled");
            return true;
        }
        plugin.messages().send(player, "list-header",
                "unlocked", String.valueOf(service.unlockedCount(player)),
                "total", String.valueOf(service.total()));
        for (AchievementDef.Category category : AchievementDef.Category.values()) {
            plugin.messages().send(player, "list-category",
                    "category", Messages.trusted(plugin.messages().raw("category." + category.id())));
            for (AchievementDef def : AchievementRegistry.byCategory(category)) {
                boolean done = service.isUnlocked(player, def.id());
                String rawName = plugin.messages().raw("ach." + def.id() + ".name");
                plugin.messages().send(player, done ? "list-line-done" : "list-line-open",
                        "name", Messages.trusted(rawName.isBlank()
                                ? AchievementService.ArtifactTextFallback.humanize(def.id())
                                : rawName),
                        "current", String.valueOf(AchievementRules.displayValue(
                                service.value(player, def.metric()), def.goal())),
                        "goal", String.valueOf(def.goal()));
            }
        }
        plugin.messages().send(player, "list-footer");
        return true;
    }

    private boolean handleBadge(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "badge-usage");
            return true;
        }
        String error = service.setBadge(player, args[1]);
        if (error != null) {
            plugin.messages().send(player, error);
            return true;
        }
        String badge = service.badgeText(player);
        if (badge == null) {
            plugin.messages().send(player, "badge-removed-plain");
        } else {
            plugin.messages().send(player, "badge-set", "badge", Messages.trusted(badge));
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkachievements.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAchievementsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            AchievementDef def = AchievementRegistry.byId(args[2]);
            if (def == null) {
                plugin.messages().send(sender, "admin-unknown");
                return true;
            }
            boolean fresh = service.grant(target, def);
            plugin.messages().send(sender, fresh ? "admin-granted" : "admin-already",
                    "player", target.getName(),
                    "id", def.id());
            plugin.getLogger().info("[CHRDK_Achievements] " + sender.getName()
                    + " выдал достижение " + def.id() + " игроку " + target.getName());
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            service.store().setBadge(target.getUniqueId().toString(), null);
            service.forget(target.getUniqueId());
            plugin.messages().send(sender, "admin-reset-badge", "player", target.getName());
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("achievements")) {
            if (args.length == 1) {
                return filter(List.of("menu", "list", "badge"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("badge")) {
                List<String> ids = new ArrayList<>();
                ids.add("off");
                for (AchievementDef def : AchievementRegistry.all()) {
                    if (def.badge() != null) {
                        ids.add(def.id());
                    }
                }
                return filter(ids, args[1]);
            }
            return List.of();
        }
        if (!command.getName().equalsIgnoreCase("achadmin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("reload", "grant", "reset"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .limit(20).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            List<String> ids = new ArrayList<>();
            for (AchievementDef def : AchievementRegistry.all()) {
                ids.add(def.id());
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(low))
                .toList();
    }
}

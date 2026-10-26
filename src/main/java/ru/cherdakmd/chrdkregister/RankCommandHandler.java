package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команды донатных рангов (1.4.167).
 *
 * <p>{@code /rank} без аргументов открывает витрину-инвентарь, {@code /rank shop}
 * печатает её текстом, {@code /rank my} показывает свой ранг и срок,
 * {@code /rank top} — действующих Меценатов и Покровителей, {@code /rank rules} —
 * короткий список «что мы не продаём».</p>
 *
 * <p>{@code /rankadmin give|take|info} — ручное управление: выдать ступень на срок
 * или навсегда, снять и посмотреть состояние. Каждая правка уходит в аудит, потому
 * что меняет деньги игрока.</p>
 */
public final class RankCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("shop", "my", "top", "rules", "help");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("give", "take", "info");

    private final RegisterPlugin plugin;
    private final AccessManager access;
    private final RankMenuService menu;
    private Messages messages;

    public RankCommandHandler(RegisterPlugin plugin, AccessManager access, RankMenuService menu) {
        this.plugin = plugin;
        this.access = access;
        this.menu = menu;
        this.messages = plugin.messages();
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void refreshMessages() {
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("rankadmin")) {
            return handleAdmin(sender, args);
        }
        if (args.length == 0) {
            menu.open(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "shop", "catalog", "витрина" -> menu.sendShop(sender);
            case "my", "мой" -> {
                if (args.length >= 2) {
                    if (!sender.hasPermission("chrdkregister.rank.others")) {
                        messages.send(sender, "error.no-permission");
                        return true;
                    }
                    menu.sendMy(sender, args[1]);
                } else if (sender instanceof Player player) {
                    menu.sendMy(sender, player.getName());
                } else {
                    messages.send(sender, "usage.rank-my", "label", label);
                }
            }
            case "top", "покровители" -> menu.sendTop(sender);
            case "rules", "правила" -> menu.sendRules(sender);
            case "help", "?" -> messages.send(sender, "rank.usage", "label", label);
            default -> messages.send(sender, "rank.usage", "label", label);
        }
        return true;
    }

    /* ------------------------------- /rankadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkregister.admin")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "rankadmin.usage");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        String target = args[1];
        switch (action) {
            case "give" -> {
                messages.send(sender, "paid-only.blocked");
                return true;
            }
            case "take" -> {
                boolean removed = access.store().revokeRank(target);
                audit(sender, "rankadmin.take", target, "removed", removed);
                messages.send(sender, removed ? "rankadmin.taken" : "rankadmin.take-none",
                        "player", target);
            }
            case "info" -> {
                long now = System.currentTimeMillis();
                int level = access.store().rankLevel(target, now);
                long until = access.store().rankUntil(target);
                messages.send(sender, "rankadmin.info",
                        "player", target,
                        "level", level <= 0 ? "—" : String.valueOf(level),
                        "rank", level <= 0 ? messages.mini("rankadmin.none")
                                : Messages.trusted(access.config().ranks().decorate(level,
                                        access.config().ranks().name(level))),
                        "until", until <= 0 ? messages.raw("rank.permanent")
                                : AccessManager.formatTimestamp(until));
            }
            default -> messages.send(sender, "rankadmin.usage");
        }
        return true;
    }

    private void audit(CommandSender sender, String action, String target, Object... details) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < details.length; index += 2) {
            map.put(String.valueOf(details[index]), details[index + 1]);
        }
        map.put("action", action);
        access.audit().log(AuditLog.ADMIN_COMMAND,
                sender instanceof Player player ? player.getName() : "console",
                target, "", map);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("rankadmin");
        if (admin) {
            if (!sender.hasPermission("chrdkregister.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBCOMMANDS, args[0]);
            }
            if (args.length == 2) {
                return filter(onlineNames(), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                return filter(List.of("1", "2", "3", "4", "5"), args[2]);
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
                return filter(List.of("30", "90", "365"), args[3]);
            }
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("my")
                && sender.hasPermission("chrdkregister.rank.others")) {
            return filter(onlineNames(), args[1]);
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}

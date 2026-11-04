package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /adminpanel}: GUI для игрока и текстовые подкоманды для консоли.
 *
 * <p>Права проверяет {@link ModerationService#isStaff(CommandSender)}: это
 * либо permission {@code chrdkregister.admin.panel}, либо роль, выданная
 * через эту же панель. Поэтому в plugin.yml у команды нет жёсткого
 * {@code permission:}, иначе Bukkit отсекал бы модераторов до нашего кода.</p>
 */
public final class AdminPanelCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "players", "profile", "moderators", "journal", "mutes", "chat",
            "warn", "unwarn", "ban", "unban", "moderator",
            "mute", "unmute", "kick", "ipban", "ipunban");

    private static final List<String> DURATIONS = List.of("10m", "1h", "1d", "7d", "30");

    private final RegisterPlugin plugin;
    private final ModerationService service;
    private final AdminPanelGui gui;

    public AdminPanelCommandHandler(RegisterPlugin plugin, ModerationService service, AdminPanelGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@org.jetbrains.annotations.NotNull CommandSender sender,
                             @org.jetbrains.annotations.NotNull Command command,
                             @org.jetbrains.annotations.NotNull String label,
                             @org.jetbrains.annotations.NotNull String[] args) {
        if (!service.isStaff(sender)) {
            plugin.messages().send(sender, "admin.no-permission");
            return true;
        }
        String action = args.length == 0 ? "players" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "players" -> gui(sender, AdminPanelGui.Page.PLAYERS, "");
            case "moderators" -> gui(sender, AdminPanelGui.Page.MODERATORS, "");
            case "journal" -> gui(sender, AdminPanelGui.Page.JOURNAL, "", 0,
                    args.length > 1 ? join(args, 1) : "");
            case "profile" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                gui(sender, AdminPanelGui.Page.PROFILE, args[1]);
            }
            case "warn" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.warn(sender, args[1], join(args, 2));
            }
            case "unwarn" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                try {
                    service.removeWarn(sender, Long.parseLong(args[1]));
                } catch (NumberFormatException ignored) {
                    plugin.messages().send(sender, "admin.warn-not-found", "id", args[1]);
                }
            }
            case "ban" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.ban(sender, args[1], args.length > 2 ? join(args, 2) : "");
            }
            case "unban" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.unban(sender, args[1]);
            }
            case "mutes" -> gui(sender, AdminPanelGui.Page.MUTES, "");
            case "chat" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                gui(sender, AdminPanelGui.Page.CHAT_HISTORY, args[1]);
            }
            case "mute" -> {
                if (args.length < 4) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                long minutes = ModerationService.muteDurationMinutes(args[2]);
                if (minutes <= 0) {
                    plugin.messages().send(sender, "admin.mute-bad-duration");
                    return true;
                }
                service.mute(sender, args[1], minutes, join(args, 3));
            }
            case "unmute" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.unmute(sender, args[1]);
            }
            case "kick" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.kick(sender, args[1], join(args, 2));
            }
            case "ipban" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.ipBan(sender, args[1], join(args, 2));
            }
            case "ipunban" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                service.ipUnban(sender, args[1]);
            }
            case "moderator" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "admin.usage");
                    return true;
                }
                if (args[1].equalsIgnoreCase("add")) service.addModerator(sender, args[2]);
                else if (args[1].equalsIgnoreCase("remove")) service.removeModerator(sender, args[2]);
                else plugin.messages().send(sender, "admin.usage");
            }
            default -> plugin.messages().send(sender, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
                                      @org.jetbrains.annotations.NotNull Command command,
                                      @org.jetbrains.annotations.NotNull String alias,
                                      @org.jetbrains.annotations.NotNull String[] args) {
        if (!service.isStaff(sender)) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && action.equals("moderator")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("add", "remove").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && action.equals("mute")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return DURATIONS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if ((args.length == 2 && (action.equals("profile") || action.equals("warn")
                || action.equals("ban") || action.equals("unban") || action.equals("mute")
                || action.equals("unmute") || action.equals("kick") || action.equals("ipban")
                || action.equals("ipunban") || action.equals("chat")))
                || (args.length == 3 && action.equals("moderator"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(player.getName());
            }
            return out;
        }
        return List.of();
    }

    private void gui(CommandSender sender, AdminPanelGui.Page page, String target) {
        gui(sender, page, target, 0, "");
    }

    private void gui(CommandSender sender, AdminPanelGui.Page page, String target,
                     int index, String filter) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "admin.console-only");
            return;
        }
        gui.open(player, page, target, index, filter);
    }

    private static String join(String[] args, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(args[i]);
        }
        return out.toString();
    }
}

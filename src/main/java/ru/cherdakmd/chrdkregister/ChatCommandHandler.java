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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Handles /chat, /msg (/tell, /w, /pm), /reply, /ignore, /unignore, and /socialspy commands.
 */
public final class ChatCommandHandler implements CommandExecutor, TabCompleter {
    private static final List<String> CHAT_SUBCOMMANDS =
            List.of("global", "local", "status", "help", "clear", "broadcast", "slow", "history");
    private static final List<String> IGNORE_SUBCOMMANDS = List.of("add", "remove", "list");
    private static final List<String> TOGGLE_SUBCOMMANDS = List.of("on", "off");
    private static final List<String> MUTE_DURATIONS = List.of("10m", "30m", "1h", "6h", "1d", "7d");

    private final ChatService chatService;

    public ChatCommandHandler(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "chat" -> chatService.handleChatCommand(sender, label, args);
            case "msg" -> chatService.handlePrivateMessageCommand(sender, label, args);
            case "reply" -> chatService.handleReplyCommand(sender, label, args);
            case "ignore" -> chatService.handleIgnoreCommand(sender, label, args, false);
            case "unignore" -> chatService.handleIgnoreCommand(sender, label, args, true);
            case "socialspy" -> chatService.handleSocialSpyCommand(sender, label, args);
            case "mute" -> args.length == 0
                    ? chatService.handleMuteCommand(sender, label, args)
                    : chatService.handleMuteApply(sender, args[0], args.length >= 2 ? args[1] : "",
                            args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "");
            case "unmute" -> chatService.handleUnmuteCommand(sender, args.length >= 1 ? args[0] : "");
            default -> false;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "chat" -> completeChat(sender, args);
            case "msg" -> args.length == 1 ? completeOnlinePlayers(args[0]) : List.of();
            case "ignore", "unignore" -> completeIgnore(args);
            case "socialspy" -> args.length == 1 ? filterPrefix(TOGGLE_SUBCOMMANDS, args[0]) : List.of();
            case "mute" -> args.length == 1
                    ? completeOnlinePlayers(args[0])
                    : (args.length == 2 ? filterPrefix(MUTE_DURATIONS, args[1]) : List.of());
            case "unmute" -> args.length == 1 ? completeOnlinePlayers(args[0]) : List.of();
            default -> List.of();
        };
    }

    private List<String> completeChat(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : CHAT_SUBCOMMANDS) {
                if (!sub.startsWith(prefix)) continue;
                if ("clear".equals(sub) && !sender.hasPermission(ChatService.PERMISSION_CLEAR)) continue;
                if ("broadcast".equals(sub) && !sender.hasPermission(ChatService.PERMISSION_BROADCAST)) continue;
                if ("slow".equals(sub) && !sender.hasPermission(ChatService.PERMISSION_MUTE)) continue;
                if ("history".equals(sub) && !sender.hasPermission(ChatService.PERMISSION_HISTORY)) continue;
                matches.add(sub);
            }
            return matches;
        }
        if (args.length == 2
                && "clear".equals(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission(ChatService.PERMISSION_CLEAR)) {
            return completeOnlinePlayers(args[1]);
        }
        return List.of();
    }

    private List<String> completeIgnore(String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>(filterPrefix(IGNORE_SUBCOMMANDS, prefix));
            for (String name : completeOnlinePlayers(args[0])) {
                if (!matches.contains(name)) {
                    matches.add(name);
                }
            }
            return matches;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("add".equals(sub) || "remove".equals(sub)) {
                return completeOnlinePlayers(args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> candidates, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static List<String> completeOnlinePlayers(String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix) && seen.add(name)) {
                matches.add(name);
            }
        }
        return matches;
    }
}

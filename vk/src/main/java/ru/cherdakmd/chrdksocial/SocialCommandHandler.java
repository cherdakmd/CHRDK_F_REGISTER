package ru.cherdakmd.chrdksocial;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkvk.VkPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Команды игрока ({@code /link}, {@code /max}) и администратора ({@code /social}). */
public final class SocialCommandHandler implements CommandExecutor, TabCompleter {

    private final SocialService service;
    private final VkPlugin plugin;

    public SocialCommandHandler(SocialService service, VkPlugin plugin) {
        this.service = service;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("link")) return link(sender);
        if (name.equals("max")) return maxCommand(sender, args);
        return socialCommand(sender, args);
    }

    private boolean link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКод входа выдаётся только игроку на сервере.");
            return true;
        }
        if (!service.config().enabled()) {
            plugin.messages().send(player, "social.disabled");
            return true;
        }
        if (!service.config().mandatoryActive()) {
            plugin.messages().send(player, "social.gate-disabled");
            return true;
        }
        if (service.store().isLinked(player.getName())) {
            sender.sendMessage("§aВаш аккаунт уже привязан: " + service.status(player.getName()));
            return true;
        }
        String code = service.issueCode(player);
        plugin.messages().send(player, "social.gate-code",
                "code", code, "minutes", Math.max(1, service.config().codeTtlSeconds() / 60),
                "vk", service.config().vkCommunityUrl(), "max", service.config().maxBotUrl());
        plugin.messages().send(player, "social.gate-howto");
        if (service.config().donateActive()) {
            plugin.messages().send(player, "social.gate-donate", "url", service.config().donateUrl());
        }
        return true;
    }

    private boolean maxCommand(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "link", "код", "code" -> {
                return link(sender);
            }
            case "status" -> {
                String target = sender instanceof Player player ? player.getName() : "CONSOLE";
                sender.sendMessage("§b[MAX] §f" + service.status(target));
                return true;
            }
            case "chats" -> {
                if (!admin(sender)) return true;
                sender.sendMessage("§b[MAX] Известные chat_id: §f" + service.discoveredChats());
                return true;
            }
            default -> {
                sender.sendMessage("§e/max [status|link|chats]");
                return true;
            }
        }
    }

    private boolean socialCommand(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                String target = args.length > 1 ? args[1]
                        : sender instanceof Player player ? player.getName() : "CONSOLE";
                if (args.length > 1 && !admin(sender)) return true;
                sender.sendMessage("§b[CHRDK] §f" + service.status(target));
                return true;
            }
            case "unlink" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage("§e/social unlink <ник>");
                    return true;
                }
                sender.sendMessage("§b[CHRDK] §f" + service.unlink(args[1], sender.getName()));
                return true;
            }
            case "emergency" -> {
                if (!admin(sender)) return true;
                int minutes = 0;
                if (args.length > 1 && !args[1].equalsIgnoreCase("off")) {
                    try {
                        minutes = Integer.parseInt(args[1]);
                    } catch (NumberFormatException bad) {
                        sender.sendMessage("§cМинуты должны быть числом или off.");
                        return true;
                    }
                }
                sender.sendMessage("§b[CHRDK] §f" + service.emergency(minutes, sender.getName()));
                return true;
            }
            case "setchat" -> {
                if (!admin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage("§e/social setchat <bot|news|bridge> <chat_id>");
                    return true;
                }
                String kind = args[1].toLowerCase(Locale.ROOT);
                if (!List.of("bot", "news", "bridge").contains(kind)) {
                    sender.sendMessage("§cВид чата: bot, news или bridge.");
                    return true;
                }
                try {
                    long chatId = Long.parseLong(args[2]);
                    sender.sendMessage("§b[CHRDK] §f" + service.setChat(kind, chatId, sender.getName()));
                } catch (NumberFormatException bad) {
                    sender.sendMessage("§cchat_id должен быть числом.");
                }
                return true;
            }
            case "chats" -> {
                if (!admin(sender)) return true;
                sender.sendMessage("§b[MAX] Известные chat_id: §f" + service.discoveredChats());
                return true;
            }
            case "testnews" -> {
                if (!admin(sender)) return true;
                service.checkRelease();
                sender.sendMessage("§b[CHRDK] §fПроверка новости релиза запущена в фоне.");
                return true;
            }
            case "donor" -> {
                return donorCommand(sender, args);
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                boolean ok = plugin.reloadBundle();
                sender.sendMessage(ok ? "§aКонфигурация перезагружена." : "§cОшибка перезагрузки, см. лог.");
                return true;
            }
            default -> {
                sender.sendMessage("§e/social [status [ник]|unlink <ник>|emergency <минуты|off>|setchat <вид> <id>|chats|donor|testnews|reload]");
                return true;
            }
        }
    }

    private boolean donorCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "check" -> {
                if (args.length < 3) {
                    sender.sendMessage("§e/social donor check <ник>");
                    return true;
                }
                sender.sendMessage("§b[CHRDK] §f" + service.donorStatus(args[2]));
            }
            case "grant" -> {
                if (args.length < 4) {
                    sender.sendMessage("§e/social donor grant <ник> <причина>");
                    return true;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                sender.sendMessage("§b[CHRDK] §f" + service.donorGrant(args[2], reason, sender.getName()));
            }
            case "revoke" -> {
                if (args.length < 3) {
                    sender.sendMessage("§e/social donor revoke <ник>");
                    return true;
                }
                sender.sendMessage("§b[CHRDK] §f" + service.donorRevoke(args[2], sender.getName()));
            }
            case "list" -> sender.sendMessage("§b[CHRDK] Донат-доступы: §f" + service.donors());
            default -> sender.sendMessage("§e/social donor [check <ник>|grant <ник> <причина>|revoke <ник>|list]");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("chrdksocial.admin") || sender.isOp()) return true;
        sender.sendMessage("§cНужно право chrdksocial.admin или op.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("link")) return out;
        if (name.equals("max")) {
            if (args.length <= 1) return List.of("status", "link", "chats");
            return out;
        }
        if (args.length <= 1) {
            return List.of("status", "unlink", "emergency", "setchat", "chats", "donor", "testnews", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setchat")) {
            return List.of("bot", "news", "bridge");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("donor")) {
            return List.of("check", "grant", "revoke", "list");
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("status")
                || args[0].equalsIgnoreCase("unlink")))
                || (args.length == 3 && args[0].equalsIgnoreCase("donor")
                        && !args[1].equalsIgnoreCase("list"))) {
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) out.add(player.getName());
        }
        return out;
    }
}

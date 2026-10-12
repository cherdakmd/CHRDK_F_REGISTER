package ru.cherdakmd.chrdkclaims;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /claimadmin — reload, обход защиты, удаление приватов. */
public final class ClaimsAdminCommandHandler implements CommandExecutor, TabCompleter {

    private final ClaimsPlugin plugin;

    public ClaimsAdminCommandHandler(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chrdkclaims.admin")) {
            if (sender instanceof Player player) {
                plugin.messages().send(player, "no-permission");
            }
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadClaimsConfig();
                send(sender, "admin-reloaded");
            }
            case "bypass" -> bypass(sender);
            case "delete" -> delete(sender);
            case "deleteall" -> deleteAll(sender, args);
            case "info" -> info(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void send(CommandSender sender, String key, String... placeholders) {
        if (sender instanceof Player player) {
            plugin.messages().send(player, key, (Object[]) placeholders);
        } else {
            sender.sendMessage(plugin.messages().plain(key, (Object[]) placeholders));
        }
    }

    private void usage(CommandSender sender) {
        send(sender, "admin-usage");
    }

    private void bypass(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        if (plugin.toggleBypass(player)) {
            plugin.messages().send(player, "admin-bypass-on");
        } else {
            plugin.messages().send(player, "admin-bypass-off");
        }
    }

    private void delete(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        Claim claim = plugin.service().claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "admin-no-claim");
            return;
        }
        plugin.store().remove(claim);
        plugin.messages().send(player, "admin-deleted", "owner", claim.owner);
    }

    private void deleteAll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String name = args[1];
        List<Claim> claims = plugin.store().ofOwner(name);
        if (claims.isEmpty()) {
            send(sender, "admin-player-not-found", "name", name);
            return;
        }
        for (Claim claim : new ArrayList<>(claims)) {
            plugin.store().remove(claim);
        }
        send(sender, "admin-deleteall-done", "name", name, "count", String.valueOf(claims.size()));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String name = args[1];
        List<Claim> claims = plugin.store().ofOwner(name);
        send(sender, "list-other", "name", name, "count", String.valueOf(claims.size()));
        for (Claim claim : claims) {
            if (sender instanceof Player player) {
                plugin.messages().send(player, "list-line",
                        "world", claim.world, "x", String.valueOf(claim.x),
                        "z", String.valueOf(claim.z),
                        "paid", ClaimsService.formatDate(claim.paidUntil),
                        "sale", claim.salePrice > 0
                                ? plugin.messages().raw("list-sale",
                                        "price", String.valueOf(claim.salePrice))
                                : "");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label,
                                      String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("reload", "bypass", "delete", "deleteall", "info")) {
                if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(sub);
                }
            }
        }
        return result;
    }
}

package ru.cherdakmd.chrdkreferrals;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Команды /ref (игроки) и /refadmin (администраторы). */
public final class ReferralCommandHandler implements TabExecutor {

    private final ReferralsPlugin plugin;
    private final ReferralService service;

    public ReferralCommandHandler(ReferralsPlugin plugin, ReferralService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "ref.no-api");
            return true;
        }
        if ("refadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        return handleRef(sender, args);
    }

    /* ------------------------------- /ref ------------------------------- */

    private boolean handleRef(CommandSender sender, String[] args) {
        if (args.length >= 1 && "top".equalsIgnoreCase(args[0])) {
            handleTop(sender);
            return true;
        }
        if (args.length >= 2 && "use".equalsIgnoreCase(args[0])) {
            handleUse(sender, args[1].trim());
            return true;
        }
        if (args.length >= 1 && ("help".equalsIgnoreCase(args[0]) || args.length >= 2)) {
            plugin.messages().send(sender, "ref.usage");
            return true;
        }
        // /ref без аргументов — статус
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "ref.usage");
            return true;
        }
        plugin.messages().send(player, "ref.status-header");
        plugin.messages().send(player, "ref.status-code", "player", player.getName());
        ReferralStore.RefStats stats = service.store().statsView(player.getName());
        plugin.messages().send(player, "ref.status-stats",
                "linked", stats.linked, "paid", stats.paid, "days", stats.daysEarned);
        service.store().linkOf(player.getName()).ifPresentOrElse(
                link -> plugin.messages().send(player, "ref.status-linked-to",
                        "inviter", link.inviter,
                        "consumed", link.consumed ? plugin.messages().mini("ref.status-consumed") : ""),
                () -> plugin.messages().send(player, "ref.status-linked-none"));
        plugin.messages().send(player, "ref.how-it-works",
                "player", player.getName(), "bonus", plugin.config().bonusDaysInviter());
        return true;
    }

    private void handleUse(CommandSender sender, String inviter) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "ref.usage");
            return;
        }
        ReferralRules.LinkError error = service.tryLink(inviter, player.getName());
        String key = ReferralRules.messageKeyOf(error);
        if (error == ReferralRules.LinkError.OK) {
            plugin.messages().send(player, key, "inviter", inviter);
        } else {
            plugin.messages().send(player, key,
                    "inviter", service.store().linkOf(player.getName())
                            .map(link -> link.inviter).orElse(inviter));
        }
    }

    private void handleTop(CommandSender sender) {
        plugin.messages().send(sender, "ref.top-header");
        List<Map.Entry<String, ReferralStore.RefStats>> top = service.store().topByPaid(10);
        if (top.isEmpty()) {
            plugin.messages().send(sender, "ref.top-empty");
            return;
        }
        int index = 1;
        for (Map.Entry<String, ReferralStore.RefStats> entry : top) {
            plugin.messages().send(sender, "ref.top-line",
                    "index", index++, "player", entry.getKey(),
                    "paid", entry.getValue().paid, "days", entry.getValue().daysEarned);
        }
    }

    /* ------------------------------- /refadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadReferralConfig();
            plugin.messages().send(sender, "admin.reloaded");
            return true;
        }
        if (args.length >= 2 && "lookup".equalsIgnoreCase(args[0])) {
            String name = args[1].trim();
            ReferralStore.RefStats stats = service.store().statsView(name);
            ReferralStore.RefLink link = service.store().linkOf(name).orElse(null);
            if (stats.linked == 0 && link == null) {
                plugin.messages().send(sender, "admin.lookup-none", "player", name);
            } else {
                plugin.messages().send(sender, "admin.lookup", "player", name,
                        "linked", stats.linked, "paid", stats.paid, "days", stats.daysEarned,
                        "inviter", link == null ? "—" : link.inviter);
            }
            return true;
        }
        if (args.length >= 2 && "reset".equalsIgnoreCase(args[0])) {
            boolean removed = service.store().removeLink(args[1].trim());
            plugin.messages().send(sender, removed ? "admin.reset-ok" : "admin.reset-none",
                    "player", args[1].trim());
            return true;
        }
        plugin.messages().send(sender, "admin.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("refadmin".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("lookup", "reset", "reload").stream()
                        .filter(option -> option.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if (args.length == 1) {
            List.of("use", "top").stream().filter(option -> option.startsWith(prefix)).forEach(matches::add);
        }
        return matches;
    }
}

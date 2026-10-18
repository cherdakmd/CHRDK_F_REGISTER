package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /claimadmin — reload, обход защиты, выдача/передача, очистка мира, рента и статистика. */
public final class ClaimsAdminCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("reload", "bypass", "delete", "deleteall",
            "info", "give", "transfer", "limit", "transferall", "purge", "rent", "stats");

    private final ClaimsPlugin plugin;

    public ClaimsAdminCommandHandler(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chrdkclaims.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "bypass" -> bypass(sender);
            case "delete" -> delete(sender);
            case "deleteall" -> deleteAll(sender, args);
            case "info" -> info(sender, args);
            case "give" -> give(sender, args);
            case "transfer" -> transfer(sender, args);
            case "limit" -> limit(sender, args);
            case "transferall" -> transferAll(sender, args);
            case "purge" -> purge(sender, args);
            case "rent" -> rent(sender);
            case "stats" -> stats(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        plugin.messages().send(sender, "admin-usage");
    }

    private void reload(CommandSender sender) {
        plugin.reloadClaimsConfig();
        plugin.messages().send(sender, "admin-reloaded");
        List<String> problems = plugin.messages().validate();
        if (!problems.isEmpty()) {
            plugin.messages().send(sender, "admin-reloaded-problems",
                    "count", String.valueOf(problems.size()),
                    "example", problems.get(0));
        }
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
            plugin.messages().send(sender, "admin-player-not-found", "name", name);
            return;
        }
        for (Claim claim : new ArrayList<>(claims)) {
            plugin.store().remove(claim);
        }
        plugin.messages().send(sender, "admin-deleteall-done",
                "name", name, "count", String.valueOf(claims.size()));
        Player online = Bukkit.getPlayerExact(name);
        String notice = plugin.messages().plain("admin-deleteall-notice",
                "count", String.valueOf(claims.size()));
        if (online != null && plugin.service().isAllowed(online)) {
            plugin.messages().send(online, "admin-deleteall-notice",
                    "count", String.valueOf(claims.size()));
        } else {
            plugin.store().addNotice(name, notice);
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String name = args[1];
        List<Claim> claims = plugin.store().ofOwner(name);
        if (claims.isEmpty()) {
            plugin.messages().send(sender, "admin-player-not-found", "name", name);
            return;
        }
        plugin.messages().send(sender, "list-other", "name", name, "count", String.valueOf(claims.size()));
        for (Claim claim : claims) {
            plugin.messages().send(sender, "list-line",
                    "world", claim.world, "x", String.valueOf(claim.x),
                    "z", String.valueOf(claim.z),
                    "paid", plugin.service().formatDate(claim.paidUntil),
                    "sale", claim.salePrice > 0
                            ? plugin.messages().raw("list-sale",
                                    "price", String.valueOf(claim.salePrice))
                            : "");
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        plugin.service().giveHere(player, args[1]);
    }

    private void transfer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        plugin.service().adminTransferHere(player, args[1]);
    }

    /** Персональный лимит: без числа — показать, «сброс» — вернуть общий. */
    private void limit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String name = args[1];
        int base = plugin.service().baseLimitOf(name);
        if (args.length < 3) {
            Integer override = plugin.store().limitOverride(name);
            plugin.messages().send(sender, "admin-limit-info",
                    "name", name,
                    "limit", String.valueOf(plugin.service().limitOf(name)),
                    "base", String.valueOf(base),
                    "override", override == null
                            ? ""
                            : plugin.messages().raw("admin-limit-override-part"));
            return;
        }
        String raw = args[2];
        if (raw.equalsIgnoreCase("сброс") || raw.equalsIgnoreCase("reset")
                || raw.equalsIgnoreCase("default")) {
            if (plugin.store().clearLimitOverride(name)) {
                plugin.messages().send(sender, "admin-limit-cleared",
                        "name", name, "limit", String.valueOf(plugin.service().limitOf(name)));
            } else {
                plugin.messages().send(sender, "admin-limit-none",
                        "name", name, "limit", String.valueOf(base));
            }
            return;
        }
        Integer value = parseAmount(raw);
        if (value == null || value < 1 || value > 100_000) {
            plugin.messages().send(sender, "admin-limit-invalid");
            return;
        }
        plugin.store().setLimitOverride(name, value);
        plugin.messages().send(sender, "admin-limit-set", "name", name,
                "limit", String.valueOf(value));
        Player online = Bukkit.getPlayerExact(name);
        int claims = plugin.store().count(name);
        if (claims <= value) {
            return;
        }
        String notice = plugin.messages().plain("admin-limit-notice",
                "limit", String.valueOf(value), "claims", String.valueOf(claims));
        if (online != null && plugin.service().isAllowed(online)) {
            plugin.messages().send(online, "admin-limit-notice",
                    "limit", String.valueOf(value), "claims", String.valueOf(claims));
        } else {
            plugin.store().addNotice(name, notice);
        }
    }

    /** Массовая передача всех приватов, казны и вестей — сценарий смены ника. */
    private void transferAll(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        String from = args[1];
        String to = args[2];
        if (from.equalsIgnoreCase(to)) {
            plugin.messages().send(sender, "admin-transferall-self");
            return;
        }
        ClaimStore.TransferSummary summary = plugin.store().transferAll(from, to);
        if (summary.claims() == 0 && summary.treasury() == 0
                && summary.notices() == 0 && !summary.limitMoved()) {
            plugin.messages().send(sender, "admin-transferall-none", "name", from);
            return;
        }
        plugin.messages().send(sender, "admin-transferall-done",
                "from", from, "name", to,
                "claims", String.valueOf(summary.claims()),
                "treasury", String.valueOf(summary.treasury()),
                "notices", String.valueOf(summary.notices()));
        String notice = plugin.messages().plain("admin-transferall-received",
                "from", from,
                "claims", String.valueOf(summary.claims()),
                "treasury", String.valueOf(summary.treasury()));
        Player online = Bukkit.getPlayerExact(to);
        if (online != null && plugin.service().isAllowed(online)) {
            plugin.messages().send(online, "admin-transferall-received",
                    "from", from,
                    "claims", String.valueOf(summary.claims()),
                    "treasury", String.valueOf(summary.treasury()));
        } else {
            plugin.store().addNotice(to, notice);
        }
    }

    private Integer parseAmount(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void purge(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String world = args[1];
        int count = plugin.store().removeWorld(world);
        if (count == 0) {
            plugin.messages().send(sender, "admin-purge-none", "world", world);
            return;
        }
        plugin.messages().send(sender, "admin-purge-done",
                "world", world, "count", String.valueOf(count));
    }

    private void rent(CommandSender sender) {
        ClaimsService.UpkeepResult result = plugin.service().processUpkeep(System.currentTimeMillis());
        plugin.messages().send(sender, "admin-rent-done",
                "charged", String.valueOf(result.charged()),
                "released", String.valueOf(result.released()),
                "debt", String.valueOf(result.debtClaims()));
    }

    private void stats(CommandSender sender) {
        List<Claim> claims = plugin.store().all();
        plugin.messages().send(sender, "admin-stats-header");
        if (claims.isEmpty()) {
            plugin.messages().send(sender, "admin-stats-empty");
            return;
        }
        Map<String, Integer> byOwner = new LinkedHashMap<>();
        int debt = 0;
        for (Claim claim : claims) {
            byOwner.merge(claim.owner, 1, Integer::sum);
            if (claim.debtDays > 0) {
                debt++;
            }
        }
        plugin.messages().send(sender, "admin-stats-general",
                "claims", String.valueOf(claims.size()),
                "owners", String.valueOf(byOwner.size()),
                "treasury", String.valueOf(plugin.store().totalTreasury()),
                "debt", String.valueOf(debt));
        plugin.messages().send(sender, "admin-stats-economy",
                "bought", String.valueOf(plugin.store().chunksClaimedTotal()),
                "spent", String.valueOf(plugin.store().diamondsSpent()),
                "tax", String.valueOf(plugin.store().taxBurned()));
        List<Map.Entry<String, Integer>> top = new ArrayList<>(byOwner.entrySet());
        top.sort((left, right) -> {
            int byCount = Integer.compare(right.getValue(), left.getValue());
            return byCount != 0 ? byCount
                    : String.CASE_INSENSITIVE_ORDER.compare(left.getKey(), right.getKey());
        });
        int place = 0;
        for (Map.Entry<String, Integer> entry : top) {
            if (++place > 5) {
                break;
            }
            plugin.messages().send(sender, "admin-stats-top",
                    "place", String.valueOf(place),
                    "name", entry.getKey(),
                    "count", String.valueOf(entry.getValue()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label,
                                      String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("transferall")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("limit")) {
            result.add("сброс");
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give")
                || args[0].equalsIgnoreCase("transfer")
                || args[0].equalsIgnoreCase("transferall")
                || args[0].equalsIgnoreCase("limit")
                || args[0].equalsIgnoreCase("deleteall")
                || args[0].equalsIgnoreCase("info"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(world.getName());
                }
            }
        }
        return result;
    }
}

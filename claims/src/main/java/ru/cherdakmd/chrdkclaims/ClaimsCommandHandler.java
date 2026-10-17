package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /claim — покупка, управление, казна, продажа, карта приватов. */
public final class ClaimsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "info", "map", "list", "unclaim",
            "add", "remove", "deposit", "withdraw", "balance", "sell", "sellback", "buy", "transfer");

    private final ClaimsPlugin plugin;

    public ClaimsCommandHandler(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().plain("player-only"));
            return true;
        }
        if (!player.hasPermission("chrdkclaims.use") && !player.hasPermission("chrdkclaims.admin")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            buy(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> plugin.messages().send(player, "usage");
            case "info" -> info(player);
            case "map" -> map(player);
            case "list" -> list(player);
            case "unclaim" -> {
                if (ready(player)) {
                    service().unclaim(player);
                }
            }
            case "sell" -> sell(player, args);
            case "sellback" -> {
                if (ready(player)) {
                    service().sellback(player);
                }
            }
            case "buy" -> {
                if (ready(player)) {
                    service().buyFromPlayer(player);
                }
            }
            case "transfer" -> transfer(player, args);
            case "add" -> members(player, args, true);
            case "remove" -> members(player, args, false);
            case "deposit" -> deposit(player, args);
            case "withdraw" -> withdraw(player, args);
            case "balance" -> balance(player);
            default -> plugin.messages().send(player, "unknown-sub");
        }
        return true;
    }

    private ClaimsService service() {
        return plugin.service();
    }

    private void buy(Player player) {
        if (!ready(player)) {
            return;
        }
        service().buy(player);
    }

    /** Покупка/казна/продажа — только для авторизованных. */
    private boolean ready(Player player) {
        if (!service().isAllowed(player)) {
            plugin.messages().send(player, "need-auth");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- view --

    private void info(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        plugin.messages().send(player, "info-header",
                "x", String.valueOf(chunk.getX()),
                "z", String.valueOf(chunk.getZ()),
                "world", chunk.getWorld().getName());
        Claim claim = service().claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "info-free",
                    "price", String.valueOf(plugin.config().chunkPrice),
                    "upkeep", String.valueOf(plugin.config().upkeepPerDay));
            return;
        }
        plugin.messages().send(player, "info-owner",
                "owner", claim.owner,
                "members-count", String.valueOf(claim.members.size()),
                "members-max", String.valueOf(plugin.config().membersPerChunk));
        plugin.messages().send(player, "info-paid",
                "paid", service().formatDate(claim.paidUntil),
                "debt", claim.debtDays > 0
                        ? plugin.messages().raw("info-debt",
                                "debt-days", String.valueOf(claim.debtDays))
                        : "");
        if (claim.salePrice > 0) {
            plugin.messages().send(player, "info-sale", "price", String.valueOf(claim.salePrice));
        }
        if (claim.members.isEmpty()) {
            plugin.messages().send(player, "info-members-none");
        } else {
            plugin.messages().send(player, "info-members",
                    "members", String.join(", ", claim.members));
        }
        if (claim.isOwner(player.getName())) {
            plugin.messages().send(player, "info-you-owner");
        } else if (claim.isMember(player.getName())) {
            plugin.messages().send(player, "info-you-member");
        }
    }

    private void map(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        String me = player.getName();
        plugin.messages().send(player, "map-header");
        for (String row : ClaimMap.render(chunk.getX(), chunk.getZ(), plugin.config().mapRadius,
                (cx, cz) -> {
                    Claim claim = plugin.store().claimAt(chunk.getWorld().getName(), cx, cz);
                    if (claim == null) {
                        return ClaimMap.Cell.FREE;
                    }
                    return new ClaimMap.Cell(true, claim.canBuild(me), claim.salePrice > 0);
                })) {
            plugin.messages().send(player, "map-line", "row", row);
        }
        plugin.messages().send(player, "map-legend");
    }

    private void list(Player player) {
        List<Claim> claims = plugin.store().ofOwner(player.getName());
        if (claims.isEmpty()) {
            plugin.messages().send(player, "list-none");
            return;
        }
        plugin.messages().send(player, "list-header",
                "count", String.valueOf(claims.size()),
                "limit", String.valueOf(service().limitOf(player.getName())));
        for (Claim claim : claims) {
            plugin.messages().send(player, "list-line",
                    "world", claim.world, "x", String.valueOf(claim.x),
                    "z", String.valueOf(claim.z),
                    "paid", service().formatDate(claim.paidUntil),
                    "sale", claim.salePrice > 0
                            ? plugin.messages().raw("list-sale",
                                    "price", String.valueOf(claim.salePrice))
                            : "");
        }
    }

    private void balance(Player player) {
        List<Claim> claims = plugin.store().ofOwner(player.getName());
        boolean debt = claims.stream().anyMatch(claim -> claim.debtDays > 0);
        int treasury = plugin.store().treasuryOf(player.getName());
        plugin.messages().send(player, "balance-line",
                "treasury", String.valueOf(treasury),
                "claims", String.valueOf(claims.size()),
                "limit", String.valueOf(service().limitOf(player.getName())),
                "upkeep", String.valueOf(plugin.config().upkeepPerDay),
                "debt", debt ? plugin.messages().raw("balance-debt") : "");
        long earliest = claims.stream()
                .filter(claim -> plugin.config().worldEnabled(claim.world))
                .mapToLong(claim -> claim.paidUntil)
                .min()
                .orElse(0L);
        if (earliest > 0) {
            plugin.messages().send(player, "balance-due",
                    "date", service().formatDate(earliest),
                    "balance", String.valueOf(treasury),
                    "need", String.valueOf(plugin.config().upkeepPerDay));
        }
    }

    // ------------------------------------------------------------ mutations --

    private void sell(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "sell-usage");
            return;
        }
        if (args[1].equalsIgnoreCase("снять") || args[1].equalsIgnoreCase("off")) {
            service().setSale(player, 0);
            return;
        }
        Integer price = parseAmount(args[1]);
        if (price == null || price < 1 || price > 1_000_000) {
            plugin.messages().send(player, "sell-invalid", "max", "1000000");
            return;
        }
        service().setSale(player, price);
    }

    private void transfer(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "transfer-usage");
            return;
        }
        service().transferHere(player, args[1]);
    }

    private void members(Player player, String[] args, boolean add) {
        if (args.length < 2) {
            plugin.messages().send(player, "usage");
            return;
        }
        boolean everywhere = args.length > 2
                && (args[2].equalsIgnoreCase("все") || args[2].equalsIgnoreCase("all"));
        if (!ready(player)) {
            return;
        }
        String name = args[1];
        if (add && name.equalsIgnoreCase(player.getName())) {
            plugin.messages().send(player, "add-self");
            return;
        }
        if (add) {
            service().addMember(player, name, everywhere);
        } else {
            service().removeMember(player, name, everywhere);
        }
    }

    private void deposit(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        int amount;
        if (args.length < 2 || args[1].equalsIgnoreCase("все")
                || args[1].equalsIgnoreCase("all")) {
            amount = DiamondItems.count(player.getInventory());
        } else {
            Integer parsed = parseAmount(args[1]);
            if (parsed == null || parsed < 1 || parsed > 1_000_000) {
                plugin.messages().send(player, "deposit-invalid", "max", "1000000");
                return;
            }
            amount = parsed;
        }
        if (amount <= 0) {
            plugin.messages().send(player, "deposit-none");
            return;
        }
        service().deposit(player, amount);
    }

    private void withdraw(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        Integer parsed = args.length > 1 ? parseAmount(args[1]) : null;
        if (parsed == null || parsed < 1 || parsed > plugin.config().withdrawMax) {
            plugin.messages().send(player, "withdraw-invalid",
                    "max", String.valueOf(plugin.config().withdrawMax));
            return;
        }
        service().withdraw(player, parsed);
    }

    private Integer parseAmount(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tab --

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
        if (args.length == 2 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("remove")
                || args[0].equalsIgnoreCase("transfer"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            result.add("снять");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deposit")) {
            result.add("все");
            return result;
        }
        return result;
    }
}
